/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.confluent.kafkarest.v2;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.ByteString;
import io.confluent.kafkarest.ConsumerReadCallback;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.entities.ConsumerInstanceConfig;
import io.confluent.kafkarest.entities.ConsumerRecord;
import io.confluent.kafkarest.entities.EmbeddedFormat;
import io.confluent.kafkarest.entities.TopicPartitionOffset;
import io.confluent.kafkarest.entities.v2.ConsumerOffsetCommitRequest;
import io.confluent.kafkarest.entities.v2.ConsumerSubscriptionRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockExtension;
import org.easymock.Mock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests basic create/read/commit/delete functionality of ConsumerManager. This only exercises the
 * functionality for binary data because it uses a mock consumer that only works with byte[] data.
 */
@ExtendWith(EasyMockExtension.class)
public class KafkaConsumerManagerTest {

  private static final String TOPIC = "topic";
  private static final int PARTITION = 1;
  private static final long BEGINNING_OFFSET = 10L;
  private static final long END_OFFSET = 20L;
  private static final long OFFSET_AT_TIME = 30L;
  private static final Instant OFFSET_TIMESTAMP = Instant.ofEpochMilli(1000L);

  private KafkaRestConfig config;

  @Mock private KafkaConsumerManager.KafkaConsumerFactory consumerFactory;

  private KafkaConsumerManager consumerManager;

  private static final String groupName = "testgroup";
  private static final String topicName = "testtopic";

  // Setup holding vars for results from callback
  private boolean sawCallback = false;
  private static Exception actualException = null;
  private static List<ConsumerRecord<ByteString, ByteString>> actualRecords = null;
  private static List<TopicPartitionOffset> actualOffsets = null;

  private Capture<Properties> capturedConsumerConfig;

  private MockConsumer<byte[], byte[]> consumer;

  @BeforeEach
  public void setUp() {
    setUpConsumer(setUpProperties());
  }

  private void setUpConsumer(Properties properties) {
    config = new KafkaRestConfig(properties);
    consumerManager = new KafkaConsumerManager(config, consumerFactory);
    consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST, groupName);
  }

  private Properties setUpProperties() {
    return setUpProperties(null);
  }

  private Properties setUpProperties(Properties props) {
    if (props == null) {
      props = new Properties();
    }
    props.setProperty(KafkaRestConfig.BOOTSTRAP_SERVERS_CONFIG, "PLAINTEXT://hostname:9092");
    props.setProperty(KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG, "1024");
    props.setProperty(KafkaRestConfig.CONSUMER_MAX_THREADS_CONFIG, "1");
    // This setting supports the testConsumerOverrides test. It is otherwise benign and should
    // not affect other tests.
    props.setProperty("consumer." + ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG, "false");

    return props;
  }

  @AfterEach
  public void tearDown() {
    consumerManager.shutdown();
  }

  private void expectCreate(MockConsumer consumer) {
    capturedConsumerConfig = Capture.newInstance();
    Properties props = EasyMock.capture(capturedConsumerConfig);
    EasyMock.expect(consumerFactory.createConsumer(props)).andStubReturn(consumer);
    EasyMock.replay(consumerFactory);
  }

  @Test
  public void getBeginningOffset_returnsBeginningOffset() {
    expectCreate(consumer);

    consumer.updateBeginningOffsets(
        singletonMap(new TopicPartition(TOPIC, PARTITION), BEGINNING_OFFSET));

    assertEquals(BEGINNING_OFFSET, consumerManager.getBeginningOffset(TOPIC, PARTITION));
  }

  @Test
  public void getEndOffset_returnsEndOffset() {
    expectCreate(consumer);

    consumer.updateEndOffsets(singletonMap(new TopicPartition(TOPIC, PARTITION), END_OFFSET));

    assertEquals(END_OFFSET, consumerManager.getEndOffset(TOPIC, PARTITION));
  }

  @Test
  public void getOffsetForTime_returnsOffset() {
    expectCreate(consumer);

    consumer.updateOffsetForTime(TOPIC, PARTITION, OFFSET_AT_TIME, OFFSET_TIMESTAMP);

    assertEquals(
        (Long) OFFSET_AT_TIME,
        consumerManager.getOffsetForTime(TOPIC, PARTITION, OFFSET_TIMESTAMP).get());
  }

  @Test
  public void testConsumerOverrides() {
    final Capture<Properties> consumerConfig = Capture.newInstance();
    EasyMock.expect(consumerFactory.createConsumer(EasyMock.capture(consumerConfig)))
        .andReturn(consumer);

    EasyMock.replay(consumerFactory);

    consumerManager.createConsumer(groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));
    // The exclude.internal.topics setting is overridden via the constructor when the
    // ConsumerManager is created, and we can make sure it gets set properly here.
    assertEquals(
        "false", consumerConfig.getValue().get(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG));

    EasyMock.verify(consumerFactory);
  }

  /** Response should return no sooner than KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG */
  @Test
  public void testConsumerRequestTimeoutms() throws Exception {
    Properties props = setUpProperties(new Properties());
    props.setProperty(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG, "2500");
    setUpConsumer(props);

    expectCreate(consumer);
    String cid =
        consumerManager.createConsumer(
            groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));
    consumerManager.subscribe(
        groupName, cid, new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));

    readFromDefault(cid);
    Thread.sleep((long) (config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG) * 0.5));
    assertFalse(sawCallback, "Callback failed early");
    Thread.sleep((long) (config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG) * 0.7));
    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
  }

  /** Response should return no sooner than KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG */
  @Test
  public void testConsumerWaitMs() throws Exception {
    Properties props = setUpProperties(new Properties());
    Integer expectedRequestTimeoutms = 400;
    props.setProperty(
        KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG, expectedRequestTimeoutms.toString());
    setUpConsumer(props);

    expectCreate(consumer);
    schedulePoll();

    String cid =
        consumerManager.createConsumer(
            groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));
    consumerManager.subscribe(
        groupName, cid, new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));
    consumer.rebalance(Collections.singletonList(new TopicPartition(topicName, 0)));
    consumer.updateBeginningOffsets(singletonMap(new TopicPartition(topicName, 0), 0L));

    readFromDefault(cid);
    long startTime = System.currentTimeMillis();
    while ((System.currentTimeMillis() - startTime) < expectedRequestTimeoutms) {
      assertFalse(sawCallback);
      Thread.sleep(40);
    }
    Thread.sleep((long) (expectedRequestTimeoutms * 0.5));
    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
  }

  /**
   * When min.bytes is not fulfilled, we should return after consumer.request.timeout.ms When
   * min.bytes is fulfilled, we should return immediately
   */
  @Test
  public void testConsumerRequestTimeoutmsAndMinBytes() throws Exception {
    Properties props = setUpProperties(new Properties());
    props.setProperty(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG, "1303");
    props.setProperty(KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG, "5");
    setUpConsumer(props);

    expectCreate(consumer);

    String cid =
        consumerManager.createConsumer(
            groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));
    consumerManager.subscribe(
        groupName, cid, new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));
    consumer.rebalance(Collections.singletonList(new TopicPartition(topicName, 0)));
    consumer.updateBeginningOffsets(singletonMap(new TopicPartition(topicName, 0), 0L));

    long startTime = System.currentTimeMillis();
    readFromDefault(cid);
    int expectedRequestTimeoutms =
        config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG);
    while ((System.currentTimeMillis() - startTime) < expectedRequestTimeoutms) {
      assertFalse(sawCallback);
      Thread.sleep(100);
    }
    Thread.sleep(200);
    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
    assertTrue(actualRecords.isEmpty(), "Records returned not empty");

    sawCallback = false;
    final List<ConsumerRecord<ByteString, ByteString>> referenceRecords = schedulePoll();
    readFromDefault(cid);
    Thread.sleep(expectedRequestTimeoutms / 2); // should return in less time

    assertEquals(referenceRecords, actualRecords, "Records returned not as expected");
    assertTrue(sawCallback, "Callback not called");
    assertNull(actualException, "Callback exception");
  }

  /**
   * Should return more than min bytes of records and less than max bytes. Should not poll() twice
   * after min bytes have been reached
   */
  @Test
  public void testConsumerMinAndMaxBytes() throws Exception {
    ConsumerRecord<ByteString, ByteString> sampleRecord = binaryConsumerRecord(0);
    int sampleRecordSize = sampleRecord.getKey().size() + sampleRecord.getValue().size();
    // we expect all the records from the first poll to be returned
    Properties props = setUpProperties(new Properties());
    props.setProperty(
        KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG, Integer.toString(sampleRecordSize));
    props.setProperty(
        KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG, Integer.toString(sampleRecordSize * 10));
    setUpConsumer(props);

    final List<ConsumerRecord<ByteString, ByteString>> scheduledRecords = schedulePoll();
    final List<ConsumerRecord<ByteString, ByteString>> referenceRecords =
        Arrays.asList(scheduledRecords.get(0), scheduledRecords.get(1), scheduledRecords.get(2));
    schedulePoll(3);

    expectCreate(consumer);
    String cid =
        consumerManager.createConsumer(
            groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));
    consumerManager.subscribe(
        groupName, cid, new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));
    consumer.rebalance(Collections.singletonList(new TopicPartition(topicName, 0)));
    consumer.updateBeginningOffsets(singletonMap(new TopicPartition(topicName, 0), 0L));

    readFromDefault(cid);
    Thread.sleep(
        (long)
            (Integer.parseInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_DEFAULT)
                * 0.5)); // should return sooner since min bytes hit

    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
    assertEquals(referenceRecords, actualRecords, "Records returned not as expected");
  }

  @Test
  public void testConsumeMinBytesIsOverridablePerConsumer() throws Exception {
    ConsumerRecord<ByteString, ByteString> sampleRecord = binaryConsumerRecord(0);
    int sampleRecordSize = sampleRecord.getKey().size() + sampleRecord.getValue().size();
    Properties props = setUpProperties(new Properties());
    props.setProperty(
        KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG, Integer.toString(sampleRecordSize * 5));
    props.setProperty(
        KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG, Integer.toString(sampleRecordSize * 6));
    setUpConsumer(props);

    final List<ConsumerRecord<ByteString, ByteString>> scheduledRecords = schedulePoll();
    // global settings would make the consumer call poll twice and get more than 3 records,
    // overridden settings should make him poll once since the min bytes will be reached
    final List<ConsumerRecord<ByteString, ByteString>> referenceRecords =
        Arrays.asList(scheduledRecords.get(0), scheduledRecords.get(1), scheduledRecords.get(2));
    schedulePoll(3);

    expectCreate(consumer);
    // we expect three records to be returned since the setting is overridden and poll() wont be
    // called a second time
    ConsumerInstanceConfig config =
        ConsumerInstanceConfig.create(
            /* id= */ null,
            /* name= */ null,
            EmbeddedFormat.BINARY,
            /* autoOffsetReset= */ null,
            /* autoCommitEnable= */ null,
            /* responseMinBytes= */ sampleRecordSize * 2,
            /* requestWaitMs= */ null);
    String cid = consumerManager.createConsumer(groupName, config);
    consumerManager.subscribe(
        groupName, cid, new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));
    consumer.rebalance(Collections.singletonList(new TopicPartition(topicName, 0)));
    consumer.updateBeginningOffsets(singletonMap(new TopicPartition(topicName, 0), 0L));

    readFromDefault(cid);
    Thread.sleep(
        (long)
            (Integer.parseInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_DEFAULT)
                * 0.5)); // should return sooner since min bytes hit

    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
    assertEquals(referenceRecords, actualRecords, "Records returned not as expected");
  }

  @Test
  public void testConsumerNormalOps() throws InterruptedException, ExecutionException {
    final List<ConsumerRecord<ByteString, ByteString>> referenceRecords =
        bootstrapConsumer(consumer);

    sawCallback = false;
    actualException = null;
    actualRecords = null;
    readFromDefault(consumer.cid());
    Thread.sleep(
        (long) (Integer.parseInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_DEFAULT) * 1.10));

    assertTrue(sawCallback, "Callback failed to fire");
    assertNull(actualException, "No exception in callback");
    assertEquals(referenceRecords, actualRecords, "Records returned not as expected");
    sawCallback = false;
    actualException = null;
    actualOffsets = null;
    ConsumerOffsetCommitRequest commitRequest = null; // Commit all offsets
    consumerManager
        .commitOffsets(
            groupName,
            consumer.cid(),
            null,
            commitRequest,
            new KafkaConsumerManager.CommitCallback() {
              @Override
              public void onCompletion(List<TopicPartitionOffset> offsets, Exception e) {
                sawCallback = true;

                actualException = e;
                actualOffsets = offsets;
              }
            })
        .get();
    assertTrue(sawCallback, "Callback not called");
    assertNull(actualException, "Callback exception");
    // Mock consumer doesn't handle offsets, so we just check we get some output for the
    // right partitions
    assertNotNull(actualOffsets, "Callback Offsets");
    // TODO: Currently the values are not actually returned in the callback nor in the response.
    // assertEquals("Callback Offsets Size", 3, actualOffsets.size());

    consumerManager.deleteConsumer(groupName, consumer.cid());
  }

  // TODO ddimitrov This continues being way too flaky, even after some fix attempts.
  //  Until we fix it (KREST-2300), we should ignore it, as it might be hiding even worse errors.
  @Disabled
  @Test
  public void testBackoffMsControlsPollCalls() throws Exception {
    long timeoutMillis = 5000L;
    long backoffMillis = 500L;
    Properties props = setUpProperties();
    props.put(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(timeoutMillis));
    props.put(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG, String.valueOf(backoffMillis));
    setUpConsumer(props);
    bootstrapConsumer(consumer);
    CopyOnWriteArrayList<Long> pollTimestampsMillis = new CopyOnWriteArrayList<>();
    consumer.schedulePollTask(
        new Runnable() {
          @Override
          public void run() {
            pollTimestampsMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            consumer.schedulePollTask(this);
          }
        });
    CountDownLatch latch = new CountDownLatch(1);
    consumerManager.readRecords(
        groupName,
        consumer.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        (records, e) -> latch.countDown());

    latch.await();

    // Poll calls should look like:
    //   0. first initial poll() returns messages
    //   1. second initial poll() returns empty
    //   2..N-1. wait backoffMillis, poll() returns empty, repeat
    //   N. wait max(backoffMillis, remainder of timeoutMillis), poll() returns empty
    assertTrue(
        pollTimestampsMillis.size() >= 2,
        String.format(
            "Expected at least 2 poll calls, but got %d instead.", pollTimestampsMillis.size()));

    // We need to verify that there's no window of size backoffMillis with more than 2 poll calls,
    // and no window of size 2 * backofMillis with no poll call at all.
    for (int i = 1; i < pollTimestampsMillis.size() - 1; i++) {
      int smallWindowCount = 1;
      long lastTimestampMillis = pollTimestampsMillis.get(i);
      for (int j = i + 1; j < pollTimestampsMillis.size(); j++) {
        long delta = pollTimestampsMillis.get(j) - pollTimestampsMillis.get(i);
        if (delta <= backoffMillis) {
          smallWindowCount++;
          lastTimestampMillis = pollTimestampsMillis.get(j);
        } else {
          break;
        }
      }
      assertTrue(
          smallWindowCount <= 2,
          String.format(
              "Expected at most 2 poll calls in window [%d, %d], but got %d instead.",
              pollTimestampsMillis.get(i), lastTimestampMillis, smallWindowCount));

      assertTrue(
          pollTimestampsMillis.get(i + 1) - pollTimestampsMillis.get(i) <= 2 * backoffMillis,
          String.format(
              "Expected at least 1 poll call in window (%d, %d), but got none instead.",
              pollTimestampsMillis.get(i), pollTimestampsMillis.get(i + 1)));
    }

    long lastTimestampMillis = pollTimestampsMillis.get(pollTimestampsMillis.size() - 1);
    long timeoutTimestampMillis = pollTimestampsMillis.get(0) + timeoutMillis;
    assertTrue(
        timeoutTimestampMillis - lastTimestampMillis < 2 * backoffMillis,
        String.format(
            "Expected at least 1 poll call in window (%d, %d], but got none instead.",
            lastTimestampMillis, timeoutTimestampMillis));
  }

  @Test
  public void testBackoffMsUpdatesReadTaskExpiry() throws Exception {
    Properties props = setUpProperties();
    props.put(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG, "1000");
    config = new KafkaRestConfig(props);
    consumerManager = new KafkaConsumerManager(config, consumerFactory);
    consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST, groupName);
    bootstrapConsumer(consumer);
    consumerManager.readRecords(
        groupName,
        consumer.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        new ConsumerReadCallback<ByteString, ByteString>() {
          @Override
          public void onCompletion(
              List<ConsumerRecord<ByteString, ByteString>> records, Exception e) {
            actualException = e;
            actualRecords = records;
            sawCallback = true;
          }
        });

    Thread.sleep(100);
    KafkaConsumerManager.RunnableReadTask readTask = consumerManager.delayedReadTasks.peek();
    if (readTask == null) {
      fail("Could not get read task in time. It should not be null");
    }
    long delay = readTask.getDelay(TimeUnit.MILLISECONDS);
    assertTrue(delay < 1000);
    assertTrue(delay > 700);
  }

  @Test
  public void testConsumerExpirationIsUpdated() throws Exception {
    bootstrapConsumer(consumer);
    KafkaConsumerState state = consumerManager.getConsumerInstance(groupName, consumer.cid());
    Instant initialExpiration = state.expiration;
    consumerManager.readRecords(
        groupName,
        consumer.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        new ConsumerReadCallback<ByteString, ByteString>() {
          @Override
          public void onCompletion(
              List<ConsumerRecord<ByteString, ByteString>> records, Exception e) {
            actualException = e;
            actualRecords = records;
            sawCallback = true;
          }
        });
    Thread.sleep(100);
    assertTrue(state.expiration.isAfter(initialExpiration));
    initialExpiration = state.expiration;
    awaitRead();
    assertTrue(sawCallback, "Callback failed to fire");
    assertTrue(state.expiration.isAfter(initialExpiration));
    initialExpiration = state.expiration;

    consumerManager
        .commitOffsets(
            groupName,
            consumer.cid(),
            null,
            null,
            new KafkaConsumerManager.CommitCallback() {
              @Override
              public void onCompletion(List<TopicPartitionOffset> offsets, Exception e) {
                sawCallback = true;

                actualException = e;
                actualOffsets = offsets;
              }
            })
        .get();
    assertTrue(state.expiration.isAfter(initialExpiration));
  }

  private void awaitRead() throws InterruptedException {
    Thread.sleep(
        (long) (Integer.parseInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_DEFAULT) * 1.10));
  }

  @Disabled
  @Test
  public void testReadRecordsPopulatesDelayedReadTaskWhenExecutorFull() throws Exception {
    Properties props = setUpProperties();
    props.setProperty(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG, "1");
    config = new KafkaRestConfig(props);
    consumerManager = new KafkaConsumerManager(config, consumerFactory);
    MockConsumer consumer1 = new MockConsumer<>(OffsetResetStrategy.EARLIEST, "a");
    MockConsumer consumer2 = new MockConsumer<>(OffsetResetStrategy.EARLIEST, "b");
    MockConsumer consumer3 = new MockConsumer<>(OffsetResetStrategy.EARLIEST, "c");
    capturedConsumerConfig = Capture.newInstance();
    EasyMock.expect(consumerFactory.createConsumer(EasyMock.capture(capturedConsumerConfig)))
        .andReturn(consumer1)
        .andReturn(consumer2)
        .andReturn(consumer3);
    EasyMock.replay(consumerFactory);
    bootstrapConsumer(consumer1, false);
    bootstrapConsumer(consumer2, false);
    bootstrapConsumer(consumer3, false);

    ConsumerReadCallback callback =
        new ConsumerReadCallback<ByteString, ByteString>() {
          @Override
          public void onCompletion(
              List<ConsumerRecord<ByteString, ByteString>> records, Exception e) {
            actualException = e;
            actualRecords = records;
            sawCallback = true;
          }
        };
    consumerManager.readRecords(
        "a",
        consumer1.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        callback);
    // should go over the consumer.threads threshold and be rejected. Rejected tasks are delayed
    // between 25-75ms
    consumerManager.readRecords(
        "a",
        consumer2.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        callback);
    Thread.sleep(10000);
    for (Object a : consumerManager.delayedReadTasks) {
      long delayMs = ((KafkaConsumerManager.RunnableReadTask) a).getDelay(TimeUnit.MILLISECONDS);
      assertTrue(delayMs > 20);
      assertTrue(delayMs < 75);
    }
    assertEquals(1, consumerManager.delayedReadTasks.size());
    consumerManager.readRecords(
        "c",
        consumer3.cid(),
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        callback);
    assertEquals(2, consumerManager.delayedReadTasks.size());
    for (Object a : consumerManager.delayedReadTasks) {
      long delayMs = ((KafkaConsumerManager.RunnableReadTask) a).getDelay(TimeUnit.MILLISECONDS);
      assertTrue(delayMs > 20);
      assertTrue(delayMs < 75);
    }
  }

  private List<ConsumerRecord<ByteString, ByteString>> bootstrapConsumer(
      final MockConsumer<byte[], byte[]> consumer) {
    return bootstrapConsumer(consumer, true);
  }

  private List<ConsumerRecord<ByteString, ByteString>> bootstrapConsumer(
      final MockConsumer<byte[], byte[]> consumer, boolean toExpectCreate) {
    List<ConsumerRecord<ByteString, ByteString>> referenceRecords =
        Arrays.asList(
            ConsumerRecord.create(
                topicName, ByteString.copyFromUtf8("k1"), ByteString.copyFromUtf8("v1"), 0, 0),
            ConsumerRecord.create(
                topicName, ByteString.copyFromUtf8("k2"), ByteString.copyFromUtf8("v2"), 0, 1),
            ConsumerRecord.create(
                topicName, ByteString.copyFromUtf8("k3"), ByteString.copyFromUtf8("v3"), 0, 2));

    if (toExpectCreate) {
      expectCreate(consumer);
    }

    String cid =
        consumerManager.createConsumer(
            consumer.groupName, ConsumerInstanceConfig.create(EmbeddedFormat.BINARY));

    consumer.cid(cid);
    consumerManager.subscribe(
        consumer.groupName,
        cid,
        new ConsumerSubscriptionRecord(Collections.singletonList(topicName), null));
    consumer.rebalance(Collections.singletonList(new TopicPartition(topicName, 0)));
    consumer.updateBeginningOffsets(singletonMap(new TopicPartition(topicName, 0), 0L));
    for (ConsumerRecord<ByteString, ByteString> record : referenceRecords) {
      consumer.addRecord(
          new org.apache.kafka.clients.consumer.ConsumerRecord<>(
              record.getTopic(),
              record.getPartition(),
              record.getOffset(),
              record.getKey().toByteArray(),
              record.getValue().toByteArray()));
    }

    return referenceRecords;
  }

  private void readFromDefault(String cid) throws InterruptedException, ExecutionException {
    consumerManager.readRecords(
        groupName,
        cid,
        BinaryKafkaConsumerState.class,
        Duration.ofMillis(-1),
        Long.MAX_VALUE,
        new ConsumerReadCallback<ByteString, ByteString>() {
          @Override
          public void onCompletion(
              List<ConsumerRecord<ByteString, ByteString>> records, Exception e) {
            actualException = e;
            actualRecords = records;
            sawCallback = true;
          }
        });
  }

  private List<ConsumerRecord<ByteString, ByteString>> schedulePoll() {
    return schedulePoll(0);
  }

  private List<ConsumerRecord<ByteString, ByteString>> schedulePoll(final int fromOffset) {
    consumer.schedulePollTask(
        new Runnable() {
          @Override
          public void run() {
            consumer.addRecord(KafkaConsumerManagerTest.this.record(fromOffset));
            consumer.addRecord(KafkaConsumerManagerTest.this.record(fromOffset + 1));
            consumer.addRecord(KafkaConsumerManagerTest.this.record(fromOffset + 2));
          }
        });
    return Arrays.asList(
        binaryConsumerRecord(fromOffset),
        binaryConsumerRecord(fromOffset + 1),
        binaryConsumerRecord(fromOffset + 2));
  }

  private ConsumerRecord<ByteString, ByteString> binaryConsumerRecord(int offset) {
    return ConsumerRecord.create(
        topicName,
        ByteString.copyFromUtf8(String.format("k%d", offset)),
        ByteString.copyFromUtf8(String.format("v%d", offset)),
        0,
        offset);
  }

  private org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record(int offset) {
    return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
        topicName,
        0,
        offset,
        String.format("k%d", offset).getBytes(),
        String.format("v%d", offset).getBytes());
  }
}
