package com.pokeskies.skiescrates.data.userdata;

import com.pokeskies.skiescrates.data.Crate;
import com.pokeskies.skiescrates.data.key.Key;
import com.pokeskies.skiescrates.data.rewards.Reward;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class UserData {
    @BsonProperty("_id")
    public UUID uuid;
    @BsonProperty
    public long version;
    @BsonProperty
    public HashMap<String, CrateData> crates;
    @BsonProperty
    public HashMap<String, Integer> keys;

    public UserData(UUID uuid) {
        this.uuid = uuid;
        this.version = 0L;
        this.crates = new HashMap<>();
        this.keys = new HashMap<>();
    }

    public UserData(UUID uuid, HashMap<String, CrateData> crates, HashMap<String, Integer> keys) {
        this.uuid = uuid;
        this.version = 0L;
        this.crates = crates;
        this.keys = keys;
    }

    public UserData(UserData userData) {
        this.uuid = userData.uuid;
        this.version = userData.version;
        this.crates = cloneCrateData(userData.crates);
        this.keys = userData.keys == null ? new HashMap<>() : new HashMap<>(userData.keys);
    }

    public UserData() {}

    private static HashMap<String, CrateData> cloneCrateData(Map<String, CrateData> crates) {
        HashMap<String, CrateData> copy = new HashMap<>();
        if (crates == null) return copy;

        crates.forEach((crateId, crateData) -> {
            HashMap<String, RewardLimitData> rewards = new HashMap<>();
            if (crateData.rewards != null) {
                crateData.rewards.forEach((rewardId, rewardData) ->
                    rewards.put(rewardId, new RewardLimitData(rewardData.claimed, rewardData.time))
                );
            }
            copy.put(crateId, new CrateData(crateData.lastOpen, crateData.openCount, rewards));
        });
        return copy;
    }

    public @Nullable Long getCrateCooldown(Crate crate) {
        return Optional.ofNullable(crates.get(crate.id)).map(data -> data.lastOpen).orElse(null);
    }

    public void addCrateCooldown(Crate crate, Long lastOpen) {
        crates.computeIfAbsent(crate.id, k -> new CrateData(0L, 0, new HashMap<>())).lastOpen = lastOpen;
    }

    public void addCrateUse(Crate crate) {
        crates.computeIfAbsent(crate.id, k -> new CrateData(0L, 0, new HashMap<>())).openCount++;
    }

    public int getRewardLimits(Crate crate, Reward reward) {
        CrateData crateData = crates.get(crate.id);
        if (crateData == null) return 0;
        return crateData.getRewards().getOrDefault(reward.id, new RewardLimitData()).claimed;
    }

    public void addRewardUse(Crate crate, Reward reward) {
        CrateData crateData = crates.computeIfAbsent(crate.id, id -> new CrateData(0L, 0, new HashMap<>()));
        crateData.getRewards().merge(reward.id, new RewardLimitData(1, System.currentTimeMillis()), (oldVal, newVal) -> {
            oldVal.claimed++;
            oldVal.time = System.currentTimeMillis();
            return oldVal;
        });
    }

    public void addKeys(Key key, int amount) {
        keys.merge(key.id, amount, Integer::sum);
    }

    public boolean removeKeys(Key key, int amount) {
        if (!keys.containsKey(key.id) || keys.get(key.id) < amount) return false;
        keys.compute(key.id, (k, v) -> v > amount ? v - amount : null);
        return true;
    }

    public void setKeys(Key key, int amount) {
        keys.put(key.id, amount);
    }

    @Override
    public String toString() {
        return "UserData{" +
                "uuid=" + uuid +
                ", version=" + version +
                ", crates=" + crates +
                ", keys=" + keys +
                '}';
    }
}
