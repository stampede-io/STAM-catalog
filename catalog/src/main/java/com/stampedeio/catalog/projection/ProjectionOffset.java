package com.stampedeio.catalog.projection;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "projection_offset")
@IdClass(ProjectionOffset.Key.class)
public class ProjectionOffset {

    @Id
    @Column(name = "consumer_group")
    private String consumerGroup;

    @Id
    @Column(name = "topic")
    private String topic;

    @Id
    @Column(name = "partition_id")
    private int partitionId;

    @Column(name = "committed_offset", nullable = false)
    private long committedOffset;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectionOffset() {}

    public ProjectionOffset(String consumerGroup, String topic, int partitionId, long committedOffset) {
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.partitionId = partitionId;
        this.committedOffset = committedOffset;
        this.updatedAt = Instant.now();
    }

    public void setCommittedOffset(long offset) {
        this.committedOffset = offset;
        this.updatedAt = Instant.now();
    }

    public String getConsumerGroup() { return consumerGroup; }
    public String getTopic() { return topic; }
    public int getPartitionId() { return partitionId; }
    public long getCommittedOffset() { return committedOffset; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static class Key implements Serializable {
        private String consumerGroup;
        private String topic;
        private int partitionId;

        public Key() {}

        public Key(String consumerGroup, String topic, int partitionId) {
            this.consumerGroup = consumerGroup;
            this.topic = topic;
            this.partitionId = partitionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return partitionId == key.partitionId
                    && Objects.equals(consumerGroup, key.consumerGroup)
                    && Objects.equals(topic, key.topic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, topic, partitionId);
        }
    }
}
