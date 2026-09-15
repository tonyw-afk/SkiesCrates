package com.pokeskies.skiescrates.data.userdata;

import org.bson.codecs.pojo.annotations.BsonProperty;

import java.util.UUID;

public class UsedKeyData {
    @BsonProperty("_id")
    public UUID uuid;
    @BsonProperty
    public String keyId;
    @BsonProperty
    public Long timeUsed;
    @BsonProperty
    public UUID player;

    public UsedKeyData(UUID uuid, String keyId, Long timeUsed, UUID player) {
        this.uuid = uuid;
        this.keyId = keyId;
        this.timeUsed = timeUsed;
        this.player = player;
    }

    public UsedKeyData(UsedKeyData usedKeyData) {
        this(usedKeyData.uuid, usedKeyData.keyId, usedKeyData.timeUsed, usedKeyData.player);
    }

    public UsedKeyData() {}

    @Override
    public String toString() {
        return "UsedKeyData{" +
                "uuid=" + uuid +
                ", keyId='" + keyId + '\'' +
                ", timeUsed=" + timeUsed +
                ", player=" + player +
                '}';
    }
}
