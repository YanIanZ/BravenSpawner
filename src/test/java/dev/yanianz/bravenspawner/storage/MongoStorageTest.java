package dev.yanianz.bravenspawner.storage;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MongoStorageTest {
    MongoCollection<Document> collection;
    MongoStorage storage;
    Spawner spawner;
    final UpdateResult acknowledged = UpdateResult.acknowledged(1, 1L, null);

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        collection = mock(MongoCollection.class);
        rows();
        when(collection.replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class))).thenReturn(acknowledged);
        when(collection.deleteOne(any(Bson.class))).thenReturn(DeleteResult.acknowledged(1));
        storage = new MongoStorage(collection, Logger.getAnonymousLogger());
        storage.init();
        storage.loadAll();
        clearInvocations(collection);
        spawner = new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 1, 64, -3,
                Spawner.Kind.MOB, "PIG", 3, 2, true);
        spawner.addXp(2);
    }

    @SuppressWarnings("unchecked")
    void rows(Document... documents) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(collection.find()).thenReturn(find);
        when(find.iterator()).thenAnswer(invocation -> {
            var iterator = List.of(documents).iterator();
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
            when(cursor.next()).thenAnswer(call -> iterator.next());
            return cursor;
        });
    }

    @Test void gameplayMutationsOnlyQueueWithoutDriverIo() {
        storage.upsert(spawner);
        storage.delete(UUID.randomUUID());
        verifyNoInteractions(collection);
        assertTrue(storage.isDirty());
        assertEquals(2, storage.pendingWrites());
    }

    @Test void failedWriteKeepsDirtyRecordForRetry() {
        storage.upsert(spawner);
        when(collection.replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class)))
                .thenThrow(new MongoException("unavailable")).thenReturn(acknowledged);
        assertThrows(Storage.StorageException.class, () -> storage.flushAll(List.of(spawner)));
        assertTrue(storage.isDirty());
        assertEquals(1, storage.pendingWrites());
        storage.flushAll(List.of(spawner));
        assertFalse(storage.isDirty());
    }

    @Test void dirtyGenerationCreatedDuringWriteSurvivesAcknowledgment() {
        storage.upsert(spawner);
        when(collection.replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class)))
                .thenAnswer(invocation -> { spawner.addXp(5); storage.upsert(spawner); return acknowledged; })
                .thenReturn(acknowledged);
        storage.flushAll(List.of(spawner));
        assertEquals(1, storage.pendingWrites());
        storage.flushAll(List.of(spawner));
        assertFalse(storage.isDirty());
        var documents = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(collection, times(2)).replaceOne(any(Bson.class), documents.capture(), any(ReplaceOptions.class));
        assertEquals(2, documents.getAllValues().get(0).getInteger("xp"));
        assertEquals(7, documents.getAllValues().get(1).getInteger("xp"));
    }

    @Test void absentSnapshotNeverDeletesWithoutExplicitTombstone() {
        storage.flushAll(List.of());
        verify(collection, never()).deleteOne(any(Bson.class));
        storage.delete(spawner.id());
        storage.flushAll(List.of());
        var filter = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection).deleteOne(filter.capture());
        assertEquals(spawner.id().toString(), filter.getValue()
                .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).getString("_id").getValue());
        verify(collection, never()).drop();
        verify(collection, never()).deleteMany(any(Bson.class));
    }

    @Test void failedReadDisablesWritesInsteadOfReplacingPartialState() {
        when(collection.find()).thenThrow(new MongoException("read failed"));
        assertThrows(Storage.StorageException.class, () -> storage.loadAll());
        assertFalse(storage.isHealthy());
        storage.upsert(spawner);
        assertThrows(Storage.StorageException.class, () -> storage.flushAll(List.of(spawner)));
        verify(collection, never()).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test void corruptInventoryIsQuarantinedAndCannotBeOverwrittenOrDeleted() {
        Document corrupt = SpawnerDocumentCodec.encode(spawner);
        corrupt.put("items", "not base64");
        rows(corrupt);
        assertTrue(storage.loadAll().isEmpty());
        assertTrue(storage.isHealthy());
        storage.upsert(spawner);
        storage.delete(spawner.id());
        storage.flushAll(List.of(spawner));
        verify(collection, never()).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
        verify(collection, never()).deleteOne(any(Bson.class));
    }

    @Test void codecRoundTripsBravenFieldsWithoutConnectingToMongo() {
        spawner.pending(27);
        spawner.produced(91);
        spawner.trusted().add(UUID.randomUUID());
        spawner.corrupt();
        Spawner restored = SpawnerDocumentCodec.decode(SpawnerDocumentCodec.encode(spawner));
        assertEquals(spawner.id(), restored.id());
        assertEquals(spawner.owner(), restored.owner());
        assertEquals("world", restored.world());
        assertEquals(3, restored.stack());
        assertEquals(2, restored.tier());
        assertEquals(2, restored.storedXp());
        assertEquals(27, restored.pending());
        assertEquals(91, restored.produced());
        assertEquals(spawner.trusted(), restored.trusted());
        assertTrue(restored.corrupted());
        assertEquals(spawner.corruptedAt(), restored.corruptedAt());
    }
}
