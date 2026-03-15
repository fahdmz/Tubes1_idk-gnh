package mainbot;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {
    static int turnCount = 0;
    static final Random rng = new Random(42);
    static final Direction[] ALL_DIRS = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };
    static MapLocation homeBase = null;
    static MapLocation nearestAllyTower = null;
    static MapLocation targetEnemyTower = null;
    static Direction patrolDir = null;
    static int stuckCount = 0;
    static int patrolTick = 0;
    static boolean isTracingRight = true;
    static int traceTurns = 0;
    static MapLocation currentRuin = null;
    static int ruinAttemptTurns = 0;
    static final int ABANDON_THRESHOLD = 12;
    static int moppersSpawned = 0;
    static int splashersSpawned = 0;
    static int soldiersSpawned = 0;
    static final double MOPPER_REFILL = 0.50;
    static final double SOLDIER_REFILL = 0.30;
    static final double SPLASHER_REFILL = 0.30;
    static final int LEASH_SQ = 130;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        homeBase = rc.getLocation();
        patrolDir = ALL_DIRS[rng.nextInt(ALL_DIRS.length)];
        while (true) {
            turnCount++;
            try {
                UnitType t = rc.getType();
                if (t.isTowerType())
                    runTower(rc);
                else if (t == UnitType.MOPPER)
                    runMopper(rc);
                else if (t == UnitType.SOLDIER)
                    runSoldier(rc);
                else if (t == UnitType.SPLASHER)
                    runSplasher(rc);
            } catch (GameActionException e) {
            } catch (Exception e) {
            } finally {
                Clock.yield();
            }
        }
    }

    public static void runTower(RobotController rc) throws GameActionException {
        for (Message m : rc.readMessages(-1))
            decodeMsg(rc, m);
        if (!rc.isActionReady())
            return;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation myLoc = rc.getLocation();
            RobotInfo best = null;
            int minD = Integer.MAX_VALUE;
            for (RobotInfo e : enemies) {
                int d = myLoc.distanceSquaredTo(e.getLocation());
                if (d < minD) {
                    minD = d;
                    best = e;
                }
            }
            if (best != null && rc.canAttack(best.getLocation())) {
                rc.attack(best.getLocation());
                rc.setIndicatorString("ATK " + best.getType());
                return;
            }
        }
        UnitType spawnType = chooseSpawnType();
        for (Direction dir : ALL_DIRS) {
            MapLocation loc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(spawnType, loc)) {
                rc.buildRobot(spawnType, loc);
                if (spawnType == UnitType.MOPPER)
                    moppersSpawned++;
                else if (spawnType == UnitType.SPLASHER)
                    splashersSpawned++;
                else
                    soldiersSpawned++;
                rc.setIndicatorString("Spawn " + spawnType);
                return;
            }
        }
    }

    static UnitType chooseSpawnType() {
        int total = moppersSpawned + splashersSpawned + soldiersSpawned;
        if (total == 0)
            return UnitType.SOLDIER;
        if (total == 1)
            return UnitType.MOPPER;
        if (total < 5)
            return UnitType.SPLASHER;
        double spRatio = (double) splashersSpawned / total;
        double mRatio = (double) moppersSpawned / total;
        double sRatio = (double) soldiersSpawned / total;
        if (sRatio < 0.30)
            return UnitType.SOLDIER;
        if (spRatio < 0.50)
            return UnitType.SPLASHER;
        if (mRatio < 0.20)
            return UnitType.MOPPER;
        return UnitType.SOLDIER;
    }

    public static void runMopper(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        RobotInfo[] nearby = rc.senseNearbyRobots(-1);
        cacheAllyTower(rc, nearby);
        rc.setIndicatorString("M p=" + paint);
        if (paint < (int) (maxPaint * MOPPER_REFILL)) {
            rc.setIndicatorString("REFILL p=" + paint);
            doRefill(rc, myLoc);
            return;
        }
        if (nearestAllyTower != null && myLoc.distanceSquaredTo(nearestAllyTower) > LEASH_SQ) {
            rc.setIndicatorString("LEASH");
            if (rc.isMovementReady())
                moveFuzzy(rc, myLoc.directionTo(nearestAllyTower));
            return;
        }
        RobotInfo target = selectMopperTarget(rc, nearby, myLoc);
        if (target != null) {
            engageMopperTarget(rc, myLoc, target, nearby);
            return;
        }
        MapLocation paintLoc = nearestVisibleEnemyPaint(rc, myLoc);
        if (paintLoc != null) {
            int dSq = myLoc.distanceSquaredTo(paintLoc);
            rc.setIndicatorString("ChasePaint dSq=" + dSq);
            if (rc.isActionReady() && rc.canAttack(paintLoc)) {
                rc.attack(paintLoc);
            }
            if (rc.isMovementReady() && dSq > 2)
                moveFuzzy(rc, myLoc.directionTo(paintLoc));
            return;
        }
        rc.setIndicatorString("Patrol p=" + paint);
        if (rc.isMovementReady())
            mopperPatrol(rc, myLoc);
    }

    static void engageMopperTarget(RobotController rc, MapLocation myLoc,
            RobotInfo target, RobotInfo[] nearby)
            throws GameActionException {
        MapLocation tLoc = target.getLocation();
        int dSq = myLoc.distanceSquaredTo(tLoc);
        rc.setIndicatorString("Engage " + target.getType());
        if (rc.isActionReady()) {
            int cluster = countEnemiesNear(nearby, tLoc, rc.getTeam(), 4);
            Direction dir = myLoc.directionTo(tLoc);
            if (cluster >= 2 && rc.canMopSwing(dir)) {
                rc.mopSwing(dir);
            } else if (rc.canAttack(tLoc)) {
                rc.attack(tLoc);
            } else if (rc.canMopSwing(dir)) {
                rc.mopSwing(dir);
            }
        }
        if (rc.isMovementReady() && dSq > 2)
            moveFuzzy(rc, myLoc.directionTo(tLoc));
    }

    static MapLocation nearestVisibleEnemyPaint(RobotController rc, MapLocation myLoc) {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int minD = Integer.MAX_VALUE;
        for (MapInfo t : tiles) {
            if (!t.getPaint().isEnemy())
                continue;
            int d = myLoc.distanceSquaredTo(t.getMapLocation());
            if (d < minD) {
                minD = d;
                best = t.getMapLocation();
            }
        }
        return best;
    }

    static void mopperPatrol(RobotController rc, MapLocation myLoc) throws GameActionException {
        patrolTick++;
        int mapW = rc.getMapWidth(), mapH = rc.getMapHeight();
        MapLocation center = new MapLocation(mapW / 2, mapH / 2);
        MapLocation base = (furthestAllyTower != null) ? furthestAllyTower : homeBase;
        int px = base.x + (int) ((center.x - base.x) * 0.4);
        int py = base.y + (int) ((center.y - base.y) * 0.4);
        MapLocation patrolTarget = new MapLocation(
                Math.max(0, Math.min(mapW - 1, px)),
                Math.max(0, Math.min(mapH - 1, py)));
        if (myLoc.distanceSquaredTo(patrolTarget) > 4) {
            moveFuzzy(rc, myLoc.directionTo(patrolTarget));
        } else {
            if (stuckCount >= 2 || patrolTick % 6 == 0)
                patrolDir = ALL_DIRS[rng.nextInt(ALL_DIRS.length)];
            moveFuzzy(rc, patrolDir);
        }
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        int attackCost = rc.getType().attackCost;
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        RobotInfo[] nearbyBots = rc.senseNearbyRobots(-1);
        cacheAllyTower(rc, nearbyBots);
        rc.setIndicatorString("Sp p=" + paint);
        if (nearestAllyTower != null && paint < (int) (maxPaint * 0.70)
                && myLoc.distanceSquaredTo(nearestAllyTower) <= 4
                && rc.isActionReady()) {
            int need = maxPaint - paint;
            if (rc.canTransferPaint(nearestAllyTower, -need)) {
                rc.transferPaint(nearestAllyTower, -need);
                paint = rc.getPaint();
            }
        }
        if (paint < (int) (maxPaint * SPLASHER_REFILL) || paint < attackCost) {
            rc.setIndicatorString("REFILL p=" + paint);
            doRefill(rc, myLoc);
            return;
        }
        if (rc.isActionReady() && paint >= attackCost) {
            MapLocation[] paintable = new MapLocation[nearbyTiles.length];
            int ptCount = 0;
            for (MapInfo tile : nearbyTiles) {
                if (!tile.isPassable() || tile.hasRuin())
                    continue;
                PaintType p = tile.getPaint();
                if (p == PaintType.EMPTY || p.isEnemy()) {
                    paintable[ptCount++] = tile.getMapLocation();
                }
            }
            MapLocation bestTarget = null;
            int maxScore = 0;
            MapLocation[] candidates = rc.getAllLocationsWithinRadiusSquared(myLoc, 4);
            for (MapLocation cand : candidates) {
                if (!rc.canAttack(cand))
                    continue;
                int score = 0;
                for (int i = 0; i < ptCount; i++) {
                    int dSq = cand.distanceSquaredTo(paintable[i]);
                    if (dSq <= 4) {
                        PaintType curPaint = rc.senseMapInfo(paintable[i]).getPaint();
                        if (curPaint == PaintType.EMPTY) {
                            score++;
                        } else if (curPaint.isEnemy() && dSq <= 2) {
                            score++;
                        }
                    }
                }
                if (score > maxScore) {
                    maxScore = score;
                    bestTarget = cand;
                }
            }
            if (bestTarget != null) {
                rc.attack(bestTarget);
                rc.setIndicatorString("AoE @" + bestTarget + " tiles=" + maxScore);
            }
        }
        if (rc.isMovementReady()) {
            splasherNavigate(rc, nearbyTiles, myLoc);
        }
    }

    static void splasherNavigate(RobotController rc, MapInfo[] nearbyTiles, MapLocation myLoc)
            throws GameActionException {
        int interesting = 0;
        for (MapInfo t : nearbyTiles) {
            if (!t.isPassable() || t.hasRuin())
                continue;
            PaintType p = t.getPaint();
            if (p == PaintType.EMPTY || p.isEnemy())
                interesting++;
        }
        int mapArea = rc.getMapWidth() * rc.getMapHeight();
        if (interesting >= 3 && mapArea >= 900) {
            Direction bestDir = Direction.CENTER;
            double maxScore = -1.0;
            for (Direction d : ALL_DIRS) {
                if (!rc.canMove(d))
                    continue;
                MapLocation next = myLoc.add(d);
                double score = 0.0;
                for (MapInfo t : nearbyTiles) {
                    if (!t.isPassable() || t.hasRuin())
                        continue;
                    PaintType p = t.getPaint();
                    if (p == PaintType.EMPTY || p.isEnemy()) {
                        int dSq = next.distanceSquaredTo(t.getMapLocation());
                        if (dSq > 0 && dSq <= 20)
                            score += 1.0 / Math.sqrt(dSq);
                    }
                }
                score += (d.ordinal() * 0.0001);
                if (score > maxScore) {
                    maxScore = score;
                    bestDir = d;
                }
            }
            if (bestDir != Direction.CENTER) {
                moveFuzzy(rc, bestDir);
                return;
            }
        }
        int W = rc.getMapWidth(), H = rc.getMapHeight();
        MapLocation globalGoal;
        if (targetEnemyTower != null) {
            globalGoal = targetEnemyTower;
        } else {
            globalGoal = new MapLocation(W - 1 - homeBase.x, H - 1 - homeBase.y);
        }
        moveFuzzy(rc, myLoc.directionTo(globalGoal));
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        RobotInfo[] nearby = rc.senseNearbyRobots(-1);
        MapInfo[] nearTiles = rc.senseNearbyMapInfos();
        cacheAllyTower(rc, nearby);
        estimateEnemyTower(rc, nearby, myLoc);
        rc.setIndicatorString("S p=" + paint);
        if (paint < (int) (maxPaint * SOLDIER_REFILL)) {
            rc.setIndicatorString("REFILL p=" + paint);
            doRefill(rc, myLoc);
            return;
        }
        if (tryFinishAnyPattern(rc, nearTiles, myLoc)) {
            tryPaintUnderfoot(rc);
            return;
        }
        boolean isBuilder = (rc.getID() % 2 == 0) || (soldiersSpawned <= 2);
        MapInfo myRuin = isBuilder ? findSafeRuinInSector(rc, nearTiles, myLoc, nearby) : null;
        if (myRuin != null) {
            if (myRuin.getMapLocation().equals(currentRuin)) {
                ruinAttemptTurns++;
            } else {
                currentRuin = myRuin.getMapLocation();
                ruinAttemptTurns = 0;
            }
            if (ruinAttemptTurns < ABANDON_THRESHOLD) {
                if (buildAtRuin(rc, myRuin, myLoc, paint)) {
                    tryPaintUnderfoot(rc);
                    return;
                }
            } else {
                currentRuin = null;
                ruinAttemptTurns = 0;
            }
        }
        boolean attacked = false;
        if (rc.isActionReady() && targetEnemyTower != null) {
            int dSq = myLoc.distanceSquaredTo(targetEnemyTower);
            if (dSq <= rc.getType().actionRadiusSquared && rc.canAttack(targetEnemyTower)) {
                rc.attack(targetEnemyTower);
                attacked = true;
                rc.setIndicatorString("ATK TOWER");
            }
        }
        if (!attacked && rc.isActionReady()) {
            RobotInfo enemy = nearestEnemy(rc, nearby, myLoc);
            if (enemy != null && rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation());
                attacked = true;
            }
        }
        if (!attacked)
            tryPaintUnderfoot(rc);
        if (rc.isMovementReady()) {
            if (myRuin != null && ruinAttemptTurns < ABANDON_THRESHOLD) {
                moveFuzzy(rc, myLoc.directionTo(myRuin.getMapLocation()));
                rc.setIndicatorString("->ruin atmp=" + ruinAttemptTurns);
            } else if (targetEnemyTower != null
                    && myLoc.distanceSquaredTo(targetEnemyTower) < myLoc.distanceSquaredTo(homeBase)) {
                moveFuzzy(rc, myLoc.directionTo(targetEnemyTower));
                rc.setIndicatorString("->eTwr " + targetEnemyTower);
            } else {
                int quadrant = rc.getID() % 4;
                int W2 = rc.getMapWidth(), H2 = rc.getMapHeight();
                MapLocation exploreTarget = switch (quadrant) {
                    case 0 -> new MapLocation(W2 / 4, H2 / 4);
                    case 1 -> new MapLocation(3 * W2 / 4, H2 / 4);
                    case 2 -> new MapLocation(W2 / 4, 3 * H2 / 4);
                    default -> new MapLocation(3 * W2 / 4, 3 * H2 / 4);
                };
                moveFuzzy(rc, myLoc.directionTo(exploreTarget));
                rc.setIndicatorString("->Q" + quadrant + " " + exploreTarget);
            }
        }
    }

    static MapInfo findSafeRuinInSector(RobotController rc, MapInfo[] tiles,
            MapLocation myLoc, RobotInfo[] nearby) {
        int mapW = rc.getMapWidth();
        boolean left = (homeBase.x < mapW / 2);
        Team enemy = rc.getTeam().opponent();
        MapInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MapInfo t : tiles) {
            if (!t.hasRuin())
                continue;
            try {
                if (rc.senseRobotAtLocation(t.getMapLocation()) != null)
                    continue;
            } catch (GameActionException e) {
                continue;
            }
            MapLocation ruinLoc = t.getMapLocation();
            boolean contested = false;
            for (RobotInfo r : nearby) {
                if (r.getTeam() == enemy && r.getType() == UnitType.SPLASHER) {
                    if (ruinLoc.distanceSquaredTo(r.getLocation()) <= 9) {
                        contested = true;
                        break;
                    }
                }
            }
            if (contested)
                continue;
            boolean mySector = left ? (ruinLoc.x <= mapW / 2) : (ruinLoc.x > mapW / 2);
            int score = (mySector ? 10000 : 0) - myLoc.distanceSquaredTo(ruinLoc);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    static boolean buildAtRuin(RobotController rc, MapInfo ruin, MapLocation myLoc, int paint)
            throws GameActionException {
        MapLocation ruinLoc = ruin.getMapLocation();
        int dSq = myLoc.distanceSquaredTo(ruinLoc);
        if (rc.isMovementReady() && dSq > 8) {
            moveFuzzy(rc, myLoc.directionTo(ruinLoc));
            myLoc = rc.getLocation();
            dSq = myLoc.distanceSquaredTo(ruinLoc);
        }
        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc);
        }
        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc);
            rc.setTimelineMarker("Tower built", 0, 255, 0);
            currentRuin = null;
            ruinAttemptTurns = 0;
            return true;
        }
        if (rc.isActionReady()) {
            for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pt.getMark() == PaintType.EMPTY || pt.getMark() == pt.getPaint())
                    continue;
                boolean sec = (pt.getMark() == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation(), sec);
                    rc.setIndicatorString("Build @" + ruinLoc + " try=" + ruinAttemptTurns);
                    if (rc.isMovementReady() && dSq > 2)
                        moveFuzzy(rc, myLoc.directionTo(ruinLoc));
                    return true;
                }
            }
        }
        if (rc.isMovementReady() && dSq > 2) {
            moveFuzzy(rc, myLoc.directionTo(ruinLoc));
            return true;
        }
        return dSq <= 8;
    }

    static boolean tryFinishPattern(RobotController rc, MapInfo[] tiles, MapLocation myLoc, MapLocation targetRuin)
            throws GameActionException {
        if (targetRuin == null)
            return false;
        for (MapInfo t : tiles) {
            if (!t.hasRuin())
                continue;
            MapLocation ruinLoc = t.getMapLocation();
            if (!ruinLoc.equals(targetRuin))
                continue;
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc);
                rc.setTimelineMarker("Tower built", 0, 255, 0);
                currentRuin = null;
                ruinAttemptTurns = 0;
                return true;
            }
            if (!rc.isActionReady())
                continue;
            for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pt.getMark() == PaintType.EMPTY || pt.getMark() == pt.getPaint())
                    continue;
                boolean sec = (pt.getMark() == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation(), sec);
                    if (rc.isMovementReady())
                        moveFuzzy(rc, myLoc.directionTo(ruinLoc));
                    return true;
                }
            }
        }
        return false;
    }

    static boolean tryFinishAnyPattern(RobotController rc, MapInfo[] tiles, MapLocation myLoc)
            throws GameActionException {
        for (MapInfo t : tiles) {
            if (!t.hasRuin())
                continue;
            MapLocation ruinLoc = t.getMapLocation();
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc);
                rc.setTimelineMarker("Tower built ops", 0, 255, 0);
                if (ruinLoc.equals(currentRuin)) {
                    currentRuin = null;
                    ruinAttemptTurns = 0;
                }
                return true;
            }
            if (!rc.isActionReady())
                continue;
            for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pt.getMark() == PaintType.EMPTY || pt.getMark() == pt.getPaint())
                    continue;
                boolean sec = (pt.getMark() == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation(), sec);
                    if (rc.isMovementReady())
                        moveFuzzy(rc, myLoc.directionTo(ruinLoc));
                    return true;
                }
            }
        }
        return false;
    }

    static RobotInfo selectMopperTarget(RobotController rc, RobotInfo[] bots, MapLocation myLoc) {
        RobotInfo bS = null, bO = null;
        int mS = Integer.MAX_VALUE, mO = Integer.MAX_VALUE;
        Team enemy = rc.getTeam().opponent();
        for (RobotInfo r : bots) {
            if (r.getTeam() != enemy || r.getType().isTowerType())
                continue;
            int d = myLoc.distanceSquaredTo(r.getLocation());
            if (r.getType() == UnitType.SPLASHER && d < mS) {
                mS = d;
                bS = r;
            } else if (r.getType() != UnitType.SPLASHER && d < mO) {
                mO = d;
                bO = r;
            }
        }
        return (bS != null) ? bS : bO;
    }

    static RobotInfo nearestEnemy(RobotController rc, RobotInfo[] bots, MapLocation myLoc) {
        RobotInfo best = null;
        int minD = Integer.MAX_VALUE;
        Team enemy = rc.getTeam().opponent();
        for (RobotInfo r : bots) {
            if (r.getTeam() != enemy || r.getType().isTowerType())
                continue;
            int d = myLoc.distanceSquaredTo(r.getLocation());
            if (d < minD) {
                minD = d;
                best = r;
            }
        }
        return best;
    }

    static int countEnemiesNear(RobotInfo[] bots, MapLocation c, Team myTeam, int rSq) {
        int n = 0;
        for (RobotInfo r : bots)
            if (r.getTeam() != myTeam && c.distanceSquaredTo(r.getLocation()) <= rSq)
                n++;
        return n;
    }

    static void estimateEnemyTower(RobotController rc, RobotInfo[] bots, MapLocation myLoc) {
        Team enemy = rc.getTeam().opponent();
        int W = rc.getMapWidth(), H = rc.getMapHeight();
        int minD = Integer.MAX_VALUE;
        MapLocation sensed = null;
        for (RobotInfo r : bots) {
            if (r.getTeam() == enemy && r.getType().isTowerType()) {
                int d = myLoc.distanceSquaredTo(r.getLocation());
                if (d < minD) {
                    minD = d;
                    sensed = r.getLocation();
                }
            }
        }
        if (sensed != null) {
            if (!sensed.equals(targetEnemyTower))
                targetEnemyTower = sensed;
            broadcastMsg(rc, sensed);
            return;
        }
        if (targetEnemyTower != null)
            return;
        int symType = rc.getID() % 3;
        switch (symType) {
            case 0:
                targetEnemyTower = new MapLocation(homeBase.x, H - 1 - homeBase.y);
                break;
            case 1:
                targetEnemyTower = new MapLocation(W - 1 - homeBase.x, H - 1 - homeBase.y);
                break;
            default:
                targetEnemyTower = new MapLocation(W - 1 - homeBase.x, homeBase.y);
                break;
        }
    }

    static MapLocation furthestAllyTower = null;

    static void cacheAllyTower(RobotController rc, RobotInfo[] bots) {
        MapLocation myLoc = rc.getLocation();
        int minD = Integer.MAX_VALUE;
        int maxDFromHome = -1;
        for (RobotInfo r : bots) {
            if (r.getTeam() == rc.getTeam() && r.getType().isTowerType()) {
                int d = myLoc.distanceSquaredTo(r.getLocation());
                if (d < minD) {
                    minD = d;
                    nearestAllyTower = r.getLocation();
                }
                int dFromHome = homeBase.distanceSquaredTo(r.getLocation());
                if (dFromHome > maxDFromHome) {
                    maxDFromHome = dFromHome;
                    furthestAllyTower = r.getLocation();
                }
            }
        }
    }

    static void doRefill(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (rc.isMovementReady()) {
            MapLocation target = (nearestAllyTower != null) ? nearestAllyTower : homeBase;
            moveFuzzy(rc, myLoc.directionTo(target));
        }
        tryRefill(rc, nearestAllyTower);
    }

    static void tryRefill(RobotController rc, MapLocation towerLoc) throws GameActionException {
        if (towerLoc == null || !rc.isActionReady())
            return;
        if (rc.getLocation().distanceSquaredTo(towerLoc) <= 4) {
            int need = rc.getType().paintCapacity - rc.getPaint();
            if (need > 0 && rc.canTransferPaint(towerLoc, -need)) {
                rc.transferPaint(towerLoc, -need);
            }
        }
    }

    static void tryPaintUnderfoot(RobotController rc) throws GameActionException {
        if (!rc.isActionReady())
            return;
        try {
            MapLocation loc = rc.getLocation();
            MapInfo under = rc.senseMapInfo(loc);
            if (!under.getPaint().isAlly() && rc.canAttack(loc))
                rc.attack(loc);
        } catch (GameActionException ignore) {
        }
    }

    static void moveFuzzy(RobotController rc, Direction dir) throws GameActionException {
        if (!rc.isMovementReady() || dir == Direction.CENTER)
            return;
        if (rc.canMove(dir)) {
            rc.move(dir);
            stuckCount = 0;
            traceTurns = 0;
            return;
        }
        traceTurns++;
        if (traceTurns > 15) {
            isTracingRight = !isTracingRight;
            traceTurns = 0;
        }
        Direction current = dir;
        for (int i = 0; i < 7; i++) {
            current = isTracingRight ? current.rotateRight() : current.rotateLeft();
            if (rc.canMove(current)) {
                rc.move(current);
                return;
            }
        }
        stuckCount++;
    }

    static void broadcastMsg(RobotController rc, MapLocation loc) {
        try {
            int encoded = loc.x * 100 + loc.y;
            for (RobotInfo r : rc.senseNearbyRobots(-1, rc.getTeam())) {
                if (r.getType().isTowerType() && rc.canSendMessage(r.getLocation(), encoded)) {
                    rc.sendMessage(r.getLocation(), encoded);
                    break;
                }
            }
        } catch (GameActionException ignore) {
        }
    }

    static void decodeMsg(RobotController rc, Message m) {
        int data = m.getBytes();
        if (data <= 0)
            return;
        int x = data / 100, y = data % 100;
        if (x >= 0 && x < rc.getMapWidth() && y >= 0 && y < rc.getMapHeight()) {
            if (targetEnemyTower == null) {
                targetEnemyTower = new MapLocation(x, y);
            }
        }
    }
}
