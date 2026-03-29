package alternative_bots_1;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // message bitmask
    static final int MSG_RUIN_FOUND = 0x00000000;
    static final int MSG_ENEMY_TOWER = 0x10000000;
    static final int MSG_NEED_REFILL = 0x20000000;
    static final int MSG_HOLD_SPAWN = 0x30000000;

    static int encodeMsg(int type, int x, int y) {
        return type | ((x & 0x3FFF) << 14) | (y & 0x3FFF);
    }

    static int decodeMsgType(int msg) {
        return msg & 0xF0000000;
    }

    static int decodeMsgX(int msg) {
        return (msg >> 14) & 0x3FFF;
    }

    static int decodeMsgY(int msg) {
        return msg & 0x3FFF;
    }

    // sensing info per turn
    static int turnCount = 0;
    static MapLocation myLoc;
    static MapInfo[] tiles;
    static RobotInfo[] robots;
    static MapLocation[] ruins;
    static MapLocation nearestPaintTower;
    static MapLocation homeLoc = null;

    // status refill
    static boolean isRefilling = false;
    static Direction lastDirection = Direction.CENTER;

    // tower spawn tracker
    static int splasherSpawned = 0;
    static int soldierSpawned = 0;

    // info eksternal
    static MapLocation targetRuin = null;
    static MapLocation allyNeedRefill = null;
    static boolean holdSpawnSignal = false;

    public static void run(RobotController rc) throws GameActionException {
        System.out.println("Bot snowball jalan...");

        while (true) {
            turnCount++;
            try {
                myLoc = rc.getLocation();
                tiles = rc.senseNearbyMapInfos();
                robots = rc.senseNearbyRobots();
                ruins = rc.senseNearbyRuins(-1);

                updateNearestPaintTower(rc);

                // set home lokasi (cuma 1 kali)
                if (homeLoc == null) {
                    if (nearestPaintTower != null) {
                        homeLoc = nearestPaintTower;
                    } else {
                        homeLoc = myLoc;
                    }
                }

                UnitType type = rc.getType();
                if (type.isTowerType()) {
                    runTower(rc);
                } else {
                    handleRefill(rc);
                    readMessages(rc);

                    if (isRefilling) {
                        // kalo lagi refill, langsung balik ke tower
                        MapLocation refillDest = (nearestPaintTower != null) ? nearestPaintTower : homeLoc;

                        if (refillDest != null && rc.isMovementReady()) {
                            if (myLoc.distanceSquaredTo(refillDest) > 2) {
                                moveWithAdjacencyPenalty(rc, myLoc.directionTo(refillDest));
                            }
                        } else if (rc.isMovementReady() && refillDest == null) {
                            navigateGreedy(rc, true);
                        }
                        continue;
                    }

                    if (type == UnitType.SPLASHER) {
                        runSplasher(rc);
                    } else if (type == UnitType.SOLDIER) {
                        runSoldier(rc);
                    }
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    /**
     * Cek refill, anti-paralysis
     */
    static void handleRefill(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        int refillThreshold = 40;

        // Soldier dekat ruin, turunin threshold
        if (rc.getType() == UnitType.SOLDIER && targetRuin != null &&
                rc.getLocation().distanceSquaredTo(targetRuin) <= 8) {
            refillThreshold = 10;
        }

        if (paint < refillThreshold) {
            isRefilling = true;
        } else if (isRefilling && paint >= (int) (maxPaint * 0.95)) {
            isRefilling = false;
        }

        if (paint < maxPaint && nearestPaintTower != null) {
            if (myLoc.distanceSquaredTo(nearestPaintTower) <= 2 && rc.isActionReady()) {
                int drawAmount = maxPaint - paint;
                if (rc.canTransferPaint(nearestPaintTower, -drawAmount)) {
                    rc.transferPaint(nearestPaintTower, -drawAmount);
                }
            }
        }
    }

    static boolean isPaintTower(UnitType t) {
        return t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER
                || t == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    static void updateNearestPaintTower(RobotController rc) throws GameActionException {
        // hapus tower dari memory kalo udah hancur
        if (nearestPaintTower != null && rc.canSenseLocation(nearestPaintTower)) {
            RobotInfo r = rc.senseRobotAtLocation(nearestPaintTower);
            if (r == null || !r.getType().isTowerType() || r.getTeam() != rc.getTeam() || !isPaintTower(r.getType())) {
                nearestPaintTower = null;
            }
        }

        // cari paint tower terdekat
        for (RobotInfo r : robots) {
            if (r.getTeam() == rc.getTeam() && isPaintTower(r.getType())) {
                if (nearestPaintTower == null
                        || myLoc.distanceSquaredTo(r.getLocation()) < myLoc.distanceSquaredTo(nearestPaintTower)) {
                    nearestPaintTower = r.getLocation();
                }
            }
        }
    }

    /**
     * Baca dan proses pesan bitmasking
     */
    static void readMessages(RobotController rc) throws GameActionException {
        Message[] msgs = rc.readMessages(-1);
        for (Message m : msgs) {
            int data = m.getBytes();
            int type = decodeMsgType(data);
            int x = decodeMsgX(data);
            int y = decodeMsgY(data);
            MapLocation loc = new MapLocation(x, y);

            if (type == MSG_RUIN_FOUND) {
                if (targetRuin == null)
                    targetRuin = loc;
            } else if (type == MSG_NEED_REFILL) {
                allyNeedRefill = loc;
            } else if (type == MSG_HOLD_SPAWN) {
                holdSpawnSignal = true;
            }
        }
    }

    static void broadcastMessage(RobotController rc, int msgType, MapLocation target) throws GameActionException {
        if (nearestPaintTower != null) {
            int data = encodeMsg(msgType, target.x, target.y);
            if (rc.canSendMessage(nearestPaintTower, data)) {
                rc.sendMessage(nearestPaintTower, data);
            }
        }
    }

    /**
     * Tower logic - spawn unit dan upgrade
     */
    static void runTower(RobotController rc) throws GameActionException {
        holdSpawnSignal = false;

        // relay pesan dari enemy ke ally
        Message[] msgs = rc.readMessages(-1);
        for (Message m : msgs) {
            int data = m.getBytes();
            int type = decodeMsgType(data);

            if (type == MSG_HOLD_SPAWN) {
                holdSpawnSignal = true;
            }

            // kirim ke kawan
            for (RobotInfo r : robots) {
                if (r.getTeam() == rc.getTeam() && !r.getType().isTowerType()) {
                    if (rc.canSendMessage(r.getLocation(), data)) {
                        rc.sendMessage(r.getLocation(), data);
                    }
                }
            }
        }

        // broadcast ruin terdekat
        if (ruins.length > 0) {
            for (MapLocation curRuin : ruins) {
                if (!hasTower(rc, curRuin)) {
                    int ruinMsg = encodeMsg(MSG_RUIN_FOUND, curRuin.x, curRuin.y);
                    for (RobotInfo r : robots) {
                        if (r.getTeam() == rc.getTeam() && r.getType() == UnitType.SOLDIER) {
                            if (rc.canSendMessage(r.getLocation(), ruinMsg)) {
                                rc.sendMessage(r.getLocation(), ruinMsg);
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (!rc.isActionReady()) {
            // no action
        } else {
            int chips = rc.getChips();

            // upgrade tower
            UnitType myType = rc.getType();
            if (myType == UnitType.LEVEL_ONE_PAINT_TOWER || myType == UnitType.LEVEL_TWO_PAINT_TOWER ||
                    myType == UnitType.LEVEL_ONE_DEFENSE_TOWER || myType == UnitType.LEVEL_TWO_DEFENSE_TOWER ||
                    myType == UnitType.LEVEL_ONE_MONEY_TOWER || myType == UnitType.LEVEL_TWO_MONEY_TOWER) {
                if (rc.canUpgradeTower(myLoc)) {
                    rc.upgradeTower(myLoc);
                }
            }

            // spawn throttle
            int spawnThreshold = holdSpawnSignal ? 1000 : 250;

            if (chips >= spawnThreshold && rc.isActionReady()) {
                UnitType toSpawn = decideSpawnType();
                for (Direction d : directions) {
                    MapLocation spawnLoc = myLoc.add(d);
                    if (rc.canBuildRobot(toSpawn, spawnLoc)) {
                        rc.buildRobot(toSpawn, spawnLoc);
                        if (toSpawn == UnitType.SPLASHER)
                            splasherSpawned++;
                        else if (toSpawn == UnitType.SOLDIER)
                            soldierSpawned++;
                        break;
                    }
                }
            }
        }
    }

    static UnitType decideSpawnType() {
        int total = splasherSpawned + soldierSpawned;
        // Early Game difokuskan pada Soldier: 1 Splasher -> 2 Soldier -> 1 Splasher dst
        if (total == 0)
            return UnitType.SPLASHER;
        if (total == 1 || total == 2)
            return UnitType.SOLDIER;

        // Mid-Late Game: Rasio 1 Splasher : 1 Soldier (Fokus Pembangunan Ruin)
        if (soldierSpawned <= 1 * splasherSpawned) {
            return UnitType.SOLDIER;
        } else {
            return UnitType.SPLASHER;
        }
    }

    static UnitType decideTowerToBuild(RobotController rc) {
        int nt = rc.getNumberTowers();
        // urutan: Money -> Paint -> Money -> Paint...
        if (nt <= 1)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (nt == 2)
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (nt == 3)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (nt == 4)
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (nt == 5)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (nt == 6)
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        return (nt % 2 == 0) ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_DEFENSE_TOWER;
    }

    /**
     * Soldier - builder unit
     */
    static void runSoldier(RobotController rc) throws GameActionException {
        // cari ruin terdekat
        if (targetRuin == null) {
            int minDist = 999999;
            for (MapLocation r : ruins) {
                if (!hasTower(rc, r)) {
                    // cek apakah ruin udah dikerjain unit lain
                    RobotInfo[] alliesAtRuin = rc.senseNearbyRobots(r, 2, rc.getTeam());
                    boolean ruinOccupied = false;
                    for (RobotInfo ally : alliesAtRuin) {
                        if (ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID()) {
                            ruinOccupied = true;
                            break;
                        }
                    }
                    if (ruinOccupied)
                        continue;

                    int d = myLoc.distanceSquaredTo(r);
                    if (d < minDist) {
                        minDist = d;
                        targetRuin = r;
                    }
                    broadcastMessage(rc, MSG_RUIN_FOUND, r);
                }
            }
        } else {
            if (hasTower(rc, targetRuin)) {
                targetRuin = null;
            }
        }

        // kirim signal kalo cat kurang
        if (rc.getPaint() < 25) {
            broadcastMessage(rc, MSG_NEED_REFILL, myLoc);
        }

        // build tower di ruin
        if (targetRuin != null) {
            if (hasTower(rc, targetRuin)) {
                targetRuin = null;
            } else {
                UnitType towerToBuild = decideTowerToBuild(rc);
                boolean atRuin = myLoc.isAdjacentTo(targetRuin) || myLoc.equals(targetRuin);

                if (atRuin) {
                    // kirim signal hold spawn
                    int holdMsg = encodeMsg(MSG_HOLD_SPAWN, targetRuin.x, targetRuin.y);
                    if (nearestPaintTower != null && rc.canSendMessage(nearestPaintTower, holdMsg)) {
                        rc.sendMessage(nearestPaintTower, holdMsg);
                    }
                    for (RobotInfo r : robots) {
                        if (r.getTeam() == rc.getTeam() && r.getType().isTowerType()) {
                            if (rc.canSendMessage(r.getLocation(), holdMsg)) {
                                rc.sendMessage(r.getLocation(), holdMsg);
                            }
                        }
                    }

                    // coba langsung selesaikan tower
                    if (rc.canCompleteTowerPattern(towerToBuild, targetRuin)) {
                        rc.completeTowerPattern(towerToBuild, targetRuin);
                        targetRuin = null;
                    } else {
                        // coba mark pattern kalo belum ada
                        boolean alreadyMarked = false;
                        for (MapInfo tile : rc.senseNearbyMapInfos(targetRuin, 8)) {
                            if (!isObstacle(tile) && tile.getMark() != PaintType.EMPTY) {
                                alreadyMarked = true;
                                break;
                            }
                        }
                        if (!alreadyMarked && rc.canMarkTowerPattern(towerToBuild, targetRuin) && rc.getPaint() >= 25) {
                            rc.markTowerPattern(towerToBuild, targetRuin);
                        }

                        // cat tile yang perlu
                        boolean didAttack = false;
                        boolean hasEnemyTiles = false;

                        if (rc.isActionReady() && rc.getPaint() >= 10) {
                            MapLocation bestTile = null;
                            boolean bestIsSecondary = false;
                            int bestDist = 999999;

                            for (MapInfo tile : rc.senseNearbyMapInfos(targetRuin, 8)) {
                                if (isObstacle(tile))
                                    continue;
                                if (tile.getMapLocation().equals(targetRuin))
                                    continue;
                                PaintType mark = tile.getMark();
                                PaintType curPaint = tile.getPaint();
                                if (mark == PaintType.EMPTY)
                                    continue;
                                if (curPaint == mark)
                                    continue;

                                if (curPaint.isEnemy()) {
                                    hasEnemyTiles = true;
                                    continue;
                                }

                                if (rc.canAttack(tile.getMapLocation())) {
                                    int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                                    if (dist < bestDist) {
                                        bestDist = dist;
                                        bestTile = tile.getMapLocation();
                                        bestIsSecondary = (mark == PaintType.ALLY_SECONDARY);
                                    }
                                }
                            }

                            if (bestTile != null) {
                                rc.attack(bestTile, bestIsSecondary);
                                didAttack = true;
                            }
                        }

                        if (hasEnemyTiles) {
                            broadcastMessage(rc, MSG_NEED_REFILL, targetRuin);
                        }

                        // orbit ruin kalo stuck
                        if (!didAttack && rc.isMovementReady()) {
                            Direction toRuin = myLoc.directionTo(targetRuin);
                            Direction orbitDir = toRuin.rotateRight().rotateRight();
                            if (rc.canMove(orbitDir)) {
                                rc.move(orbitDir);
                                lastDirection = orbitDir;
                            } else if (rc.canMove(orbitDir.rotateRight())) {
                                rc.move(orbitDir.rotateRight());
                                lastDirection = orbitDir.rotateRight();
                            } else if (rc.canMove(toRuin.rotateLeft().rotateLeft())) {
                                rc.move(toRuin.rotateLeft().rotateLeft());
                                lastDirection = toRuin.rotateLeft().rotateLeft();
                            }
                        }
                    }
                } else if (rc.isMovementReady()) {
                    // gerak ke ruin
                    moveWithAdjacencyPenalty(rc, myLoc.directionTo(targetRuin));
                }
            }
        }

        // jalan eksplorasi kalo no ruin
        if (targetRuin == null && rc.isMovementReady()) {
            navigateGreedy(rc, false);
        }
    }

    /**
     * Splasher - attack unit
     */
    static void runSplasher(RobotController rc) throws GameActionException {
        // maksimalisasi cakupan AoE
        if (rc.isActionReady() && rc.getPaint() >= rc.getType().attackCost + 5) {
            MapLocation bestTarget = null;
            double maxScore = -1.0;

            MapLocation[] possible = rc.getAllLocationsWithinRadiusSquared(myLoc, rc.getType().actionRadiusSquared);
            for (MapLocation t : possible) {
                if (!rc.canAttack(t))
                    continue;
                double score = 0;
                for (MapInfo tile : tiles) {
                    if (isObstacle(tile) || tile.getPaint().isAlly())
                        continue;
                    int distSq = t.distanceSquaredTo(tile.getMapLocation());
                    if (distSq <= 4) {
                        if (tile.getPaint() == PaintType.EMPTY)
                            score += 1.0;
                        else if (tile.getPaint().isEnemy() && distSq <= 2)
                            score += 1.0;
                    }
                }

                // bias ekstrim untuk attack di ruin
                if (targetRuin != null) {
                    int distToRuinSq = t.distanceSquaredTo(targetRuin);
                    if (distToRuinSq <= 4) {
                        score += 100.0;
                    } else {
                        score += (25.0 / Math.max(1.0, Math.sqrt(distToRuinSq)));
                    }
                }

                if (score > maxScore) {
                    maxScore = score;
                    bestTarget = t;
                }
            }

            if (bestTarget != null && (maxScore >= 1.0)) {
                rc.attack(bestTarget);
            }
        }

        if (rc.isMovementReady()) {
            navigateGreedy(rc, false);
        }
    }

    /**
     * Utility - cek obstacle
     */
    static boolean isObstacle(MapInfo tile) {
        if (tile.isWall() || tile.hasRuin() || !tile.isPassable())
            return true;
        return false;
    }

    static boolean hasTower(RobotController rc, MapLocation loc) throws GameActionException {
        if (rc.canSenseLocation(loc)) {
            RobotInfo r = rc.senseRobotAtLocation(loc);
            return (r != null && r.getType().isTowerType());
        }
        return false;
    }

    /**
     * Navigasi greedy mencari tile kosong
     */
    static void navigateGreedy(RobotController rc, boolean fleeMode) throws GameActionException {
        MapLocation symLoc = new MapLocation(rc.getMapWidth() - 1 - myLoc.x, rc.getMapHeight() - 1 - myLoc.y);

        Direction bestDir = Direction.CENTER;
        double maxScore = -999999.0;

        for (Direction d : directions) {
            if (!rc.canMove(d))
                continue;
            MapLocation nextLoc = myLoc.add(d);
            double score = 0.0;

            // momentum bonus
            if (d == lastDirection)
                score += 10.0;

            // flee mode: hindari musuh
            if (fleeMode) {
                if (rc.canSenseLocation(nextLoc)) {
                    PaintType pt = rc.senseMapInfo(nextLoc).getPaint();
                    if (pt.isEnemy())
                        score -= 100.0;
                }
            }

            // penalty kalo deket ally
            for (RobotInfo r : robots) {
                if (r.getTeam() == rc.getTeam() && !r.getType().isTowerType()
                        && nextLoc.isAdjacentTo(r.getLocation())) {
                    score -= 50.0;
                }
            }

            // cari tile kosong/musuh
            for (MapInfo tile : tiles) {
                if (isObstacle(tile))
                    continue;
                if (tile.getPaint() == PaintType.EMPTY || tile.getPaint().isEnemy()) {
                    int distSq = nextLoc.distanceSquaredTo(tile.getMapLocation());
                    if (distSq <= 20 && distSq > 0) {
                        score += 1.0 / Math.sqrt(distSq);
                    }
                }
            }

            // kesimetrisan
            int symDist = Math.max(1, nextLoc.distanceSquaredTo(symLoc));
            score += 15.0 / Math.sqrt(symDist);

            if (score > maxScore) {
                maxScore = score;
                bestDir = d;
            }
        }

        if (bestDir != Direction.CENTER) {
            rc.move(bestDir);
            lastDirection = bestDir;
        }
    }

    static void moveWithAdjacencyPenalty(RobotController rc, Direction dir) throws GameActionException {
        if (!rc.isMovementReady() || dir == Direction.CENTER)
            return;

        Direction[] tries = { dir, dir.rotateRight(), dir.rotateLeft(), dir.rotateRight().rotateRight(),
                dir.rotateLeft().rotateLeft() };
        Direction best = null;
        double bestScore = -999999.0;

        for (Direction d : tries) {
            if (rc.canMove(d)) {
                double score = 0.0;
                MapLocation next = myLoc.add(d);

                // hindari menumpuk dengan kawan
                for (RobotInfo r : robots) {
                    if (r.getTeam() == rc.getTeam() && !r.getType().isTowerType()
                            && next.isAdjacentTo(r.getLocation())) {
                        score -= 50.0;
                    }
                }
                if (d == dir)
                    score += 50.0;

                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
        }

        if (best != null) {
            rc.move(best);
            lastDirection = best;
        }
    }
}
