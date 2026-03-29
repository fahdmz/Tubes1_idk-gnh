package alternative_bots_2;

import battlecode.common.*;
import java.util.Random;

/**
 * Bot Splasher specialist - fokus attack area & expand territory
 */
public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // arah random buat jalan
    static Direction exploreDir = directions[rng.nextInt(directions.length)];

    // lokasi paint tower terdekat
    static MapLocation nearestPaintTower = null;

    public static void run(RobotController rc) throws GameActionException {
        System.out.println("Bot Splasher mulai jalan...");

        while (true) {
            turnCount++;
            try {
                switch (rc.getType()) {
                    case SPLASHER:
                        runSplasher(rc);
                        break;
                    case LEVEL_ONE_PAINT_TOWER:
                    case LEVEL_ONE_MONEY_TOWER:
                    case LEVEL_ONE_DEFENSE_TOWER:
                    case LEVEL_TWO_PAINT_TOWER:
                    case LEVEL_TWO_MONEY_TOWER:
                    case LEVEL_TWO_DEFENSE_TOWER:
                    case LEVEL_THREE_PAINT_TOWER:
                    case LEVEL_THREE_MONEY_TOWER:
                    case LEVEL_THREE_DEFENSE_TOWER:
                        runTower(rc);
                        break;
                    default:
                        // jalan random kalau bukan Splasher atau Tower
                        if (rc.isMovementReady()) {
                            Direction dir = directions[rng.nextInt(directions.length)];
                            if (rc.canMove(dir)) {
                                rc.move(dir);
                            }
                        }
                        break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException dah terjadi");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Error di RobotPlayer");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    /**
     * Tower coba spawn Splasher
     */
    public static void runTower(RobotController rc) throws GameActionException {
        // pick arah random
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        // spawn Splasher kalo bisa
        if (rc.isActionReady() && rc.canBuildRobot(UnitType.SPLASHER, nextLoc)) {
            rc.buildRobot(UnitType.SPLASHER, nextLoc);
            System.out.println("Splasher baru spawn di " + nextLoc);
        }
    }

    /**
     * Logic splasher utama
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        // dapetin info sekitar
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        RobotInfo[] robots = rc.senseNearbyRobots();

        MapLocation myLoc = rc.getLocation();
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        int attackCost = rc.getType().attackCost;
        int lowPaint = (int) (maxPaint * 0.20);

        // update tower terdekat
        updateNearestPaintTower(rc, robots);

        // isi ulang cat kalo kurang
        if (paint < (int) (maxPaint * 0.90)) {
            tryRefill(rc, nearestPaintTower);
            paint = rc.getPaint();
        }

        // kalo cat terlalu sedikit, balik ke tower
        if (paint < lowPaint || paint < attackCost) {
            if (nearestPaintTower != null) {
                if (rc.isMovementReady()) {
                    Direction dirToTower = myLoc.directionTo(nearestPaintTower);
                    moveFuzzy(rc, dirToTower);
                }
            } else {
                if (rc.isMovementReady()) {
                    greedyNavigation(rc, tiles, myLoc);
                }
            }
            return;
        }

        // cari target attack terbaik
        if (rc.isActionReady() && paint >= attackCost) {
            MapLocation bestTarget = null;
            int maxPaintableTiles = -1;

            MapLocation[] paintableTiles = new MapLocation[tiles.length];
            int ptCount = 0;
            for (MapInfo tile : tiles) {
                if (!tile.isPassable() || tile.hasRuin())
                    continue;
                PaintType pt = tile.getPaint();
                if (pt == PaintType.EMPTY || pt.isEnemy()) {
                    paintableTiles[ptCount++] = tile.getMapLocation();
                }
            }

            // cek semua lokasi attack yang bisa
            MapLocation[] possibleTargets = rc.getAllLocationsWithinRadiusSquared(myLoc, 4);

            for (MapLocation target : possibleTargets) {
                if (!rc.canAttack(target))
                    continue;

                int paintableCount = 0;

                // hitung tile yang bisa dicat dari target ini
                for (int i = 0; i < ptCount; i++) {
                    MapLocation ptLoc = paintableTiles[i];
                    int distSq = target.distanceSquaredTo(ptLoc);
                    if (distSq <= 4) {
                        PaintType curPaint = rc.senseMapInfo(ptLoc).getPaint();
                        if (curPaint == PaintType.EMPTY) {
                            paintableCount++;
                        } else if (curPaint.isEnemy() && distSq <= 2) {
                            paintableCount++;
                        }
                    }
                }

                if (paintableCount > maxPaintableTiles) {
                    maxPaintableTiles = paintableCount;
                    bestTarget = target;
                }
            }

            // attack kalo worth it
            if (bestTarget != null && (maxPaintableTiles * 40 > rc.getPaint())) {
                rc.attack(bestTarget);
            }
        }

        // gerak mencari tile kosong
        if (rc.isMovementReady()) {
            greedyNavigation(rc, tiles, myLoc);
        }
    }

    /**
     * Gerak ke arah tile kosong terdekat
     */
    public static void greedyNavigation(RobotController rc, MapInfo[] tiles, MapLocation myLoc)
            throws GameActionException {

        int threshold = rc.getPaint() / 40;
        if (threshold < 1)
            threshold = 1;

        int totalUnpainted = 0;
        for (MapInfo tile : tiles) {
            if (!tile.isPassable() || tile.hasRuin())
                continue;
            if (tile.getPaint() == PaintType.EMPTY || tile.getPaint().isEnemy()) {
                totalUnpainted++;
            }
        }

        // kalo area sekitar udah banyak dicat, jalan ke tempat lain
        if (totalUnpainted < 5) {
            MapLocation symmetryLoc = new MapLocation(rc.getMapWidth() - 1 - myLoc.x, rc.getMapHeight() - 1 - myLoc.y);
            Direction symDir = myLoc.directionTo(symmetryLoc);
            moveFuzzy(rc, symDir);
            return;
        }

        Direction bestDir = Direction.CENTER;
        double maxHeuristic = -1.0;

        for (Direction dir : directions) {
            if (!rc.canMove(dir))
                continue;

            MapLocation nextLoc = myLoc.add(dir);
            double unpaintedHeuristic = 0.0;

            // hitung tile kosong/musuh di sekitar posisi baru
            for (MapInfo tile : tiles) {
                if (!tile.isPassable() || tile.hasRuin())
                    continue;

                if (tile.getPaint() == PaintType.EMPTY || tile.getPaint().isEnemy()) {
                    int distSq = nextLoc.distanceSquaredTo(tile.getMapLocation());
                    if (distSq <= 20 && distSq > 0) {
                        unpaintedHeuristic += 1.0 / Math.sqrt(distSq);
                    }
                }
            }

            if (unpaintedHeuristic > maxHeuristic) {
                maxHeuristic = unpaintedHeuristic;
                bestDir = dir;
            }
        }

        if (bestDir != Direction.CENTER) {
            moveFuzzy(rc, bestDir);
        } else {
            // jalan random kalo stuck
            moveFuzzy(rc, directions[rng.nextInt(directions.length)]);
        }
    }

    /**
     * Update cache tower paint terdekat
     */
    public static void updateNearestPaintTower(RobotController rc, RobotInfo[] robots) {
        MapLocation myLoc = rc.getLocation();
        int minDist = 999999;

        for (RobotInfo robot : robots) {
            if (robot.getTeam() == rc.getTeam() && robot.getType().isTowerType()) {
                int distSq = myLoc.distanceSquaredTo(robot.getLocation());
                if (distSq < minDist) {
                    minDist = distSq;
                    nearestPaintTower = robot.getLocation();
                }
            }
        }
    }

    /**
     * Coba isi ulang cat dari tower
     */
    public static void tryRefill(RobotController rc, MapLocation towerLoc) throws GameActionException {
        if (towerLoc == null)
            return;
        if (!rc.isActionReady())
            return;

        // harus dekat tower (distSq <= 2)
        if (rc.getLocation().distanceSquaredTo(towerLoc) <= 2) {
            int drawAmount = rc.getType().paintCapacity - rc.getPaint();
            if (drawAmount > 0) {
                // negative value buat withdraw
                if (rc.canTransferPaint(towerLoc, -drawAmount)) {
                    rc.transferPaint(towerLoc, -drawAmount);
                }
            }
        }
    }

    /**
     * Coba gerak, kalo tidak bisa putar arah
     */
    public static void moveFuzzy(RobotController rc, Direction dir) throws GameActionException {
        if (!rc.isMovementReady() || dir == Direction.CENTER)
            return;

        if (rc.canMove(dir)) {
            rc.move(dir);
            return;
        }

        Direction right = dir.rotateRight();
        if (rc.canMove(right)) {
            rc.move(right);
            return;
        }

        Direction left = dir.rotateLeft();
        if (rc.canMove(left)) {
            rc.move(left);
            return;
        }

        Direction right2 = right.rotateRight();
        if (rc.canMove(right2)) {
            rc.move(right2);
            return;
        }

        Direction left2 = left.rotateLeft();
        if (rc.canMove(left2)) {
            rc.move(left2);
            return;
        }
    }
}
