package com.direk.dkledger.audit.read;

import java.util.List;

/**
 * Virtual Spawner's settings and mobs.
 *
 * @param stackLimit   spawners per stack
 * @param chunkLimit   spawners (stacks) per chunk
 * @param sellDisabled true if Virtual Spawner's own selling is off (drops then go through the shops)
 * @param mobs         every mob file
 */
public record SpawnerData(int stackLimit, int chunkLimit, boolean sellDisabled, List<Mob> mobs) {

    /**
     * @param type          entity type, e.g. IRON_GOLEM
     * @param secondsPerSpawn average time between spawns
     * @param spawnerPrice  spawner-price in the mob file (-1 if none)
     * @param drops         what one spawn drops
     * @param file          the mob file, for the report
     */
    public record Mob(String type, double secondsPerSpawn, double spawnerPrice, List<Drop> drops, String file) {
    }

    /**
     * @param material     the item
     * @param perSpawn     average amount per spawn (chance × average amount)
     * @param sellPrice    Virtual Spawner's sell price for one
     */
    public record Drop(String material, double perSpawn, double sellPrice) {
    }
}
