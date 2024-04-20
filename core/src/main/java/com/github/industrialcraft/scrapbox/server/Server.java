package com.github.industrialcraft.scrapbox.server;

import clipper2.core.PathD;
import clipper2.core.PathsD;
import clipper2.core.PointD;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.github.industrialcraft.netx.LANBroadcaster;
import com.github.industrialcraft.netx.NetXServer;
import com.github.industrialcraft.netx.ServerMessage;
import com.github.industrialcraft.netx.SocketUser;
import com.github.industrialcraft.scrapbox.common.net.MessageRegistryCreator;
import com.github.industrialcraft.scrapbox.server.game.*;
import com.github.industrialcraft.scrapbox.common.net.LocalConnection;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Server {
    public static Vector2 GRAVITY = new Vector2(0, -9.81f);

    public final ArrayList<Player> players;
    public final HashMap<Integer,GameObject> gameObjects;
    private final ArrayList<GameObject> newGameObjects;
    public final World physics;
    public final Terrain terrain;
    public final ClientWorldManager clientWorldManager;
    public final NetXServer networkServer;
    private boolean stopped;
    public boolean paused;
    private int tickCount;
    private final UUID uuid;
    public Server() {
        this.uuid = UUID.randomUUID();
        this.players = new ArrayList<>();
        this.physics = new World(GRAVITY, true);
        this.terrain = new Terrain(this);
        this.terrain.registerTerrainType("dirt", new Terrain.TerrainType(2, 0.05f));
        this.terrain.registerTerrainType("stone", new Terrain.TerrainType(1, 0.3f));
        this.terrain.registerTerrainType("ice", new Terrain.TerrainType(0.f, 0.1f));
        this.terrain.registerTerrainType("slime", new Terrain.TerrainType(2f, 1f));
        this.gameObjects = new HashMap<>();
        this.newGameObjects = new ArrayList<>();
        this.clientWorldManager = new ClientWorldManager(this);
        this.networkServer = new NetXServer(0, MessageRegistryCreator.create());
        this.networkServer.start();
        this.stopped = false;
        this.tickCount = 0;
        this.physics.setContactFilter((fixtureA, fixtureB) -> {
            Filter filterA = fixtureA.getFilterData();
            Filter filterB = fixtureB.getFilterData();
            boolean collide =
                (filterA.maskBits & filterB.categoryBits) != 0 &&
                    (filterA.categoryBits & filterB.maskBits) != 0;
            if(!collide){
                return false;
            }
            Body bodyA = fixtureA.getBody();
            Body bodyB = fixtureB.getBody();
            Object userDataA = bodyA.getUserData();
            Object userDataB = bodyB.getUserData();
            if(userDataA instanceof GameObject){
                if(!((GameObject) userDataA).collidesWith(bodyA, bodyB)){
                    return false;
                }
            }
            if(userDataB instanceof GameObject){
                if(!((GameObject) userDataB).collidesWith(bodyB, bodyA)){
                    return false;
                }
            }
            return true;
        });
    }
    public LocalConnection joinLocalPlayer(){
        ConcurrentLinkedQueue<Object> write = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Object> read = new ConcurrentLinkedQueue<>();
        this.addPlayer(new Player(this, new LocalConnection(write, read)));
        return new LocalConnection(read, write);
    }
    public  <T extends GameObject> T spawnGameObject(Vector2 position, float rotation, GameObject.GameObjectSpawner<T> spawner, UUID uuid){
        T gameObject = spawner.spawn(position, rotation, this);
        if(uuid != null){
            gameObject.uuid = uuid;
        }
        this.newGameObjects.add(gameObject);
        return gameObject;
    }
    public GameObject spawnGameObject(Vector2 position, float rotation, String type, UUID uuid){
        if(type.equals("frame")){
            return spawnGameObject(position, rotation, FrameGameObject::new, uuid);
        }
        if(type.equals("wheel")){
            return spawnGameObject(position, rotation, WheelGameObject::new, uuid);
        }
        if(type.equals("balloon")){
            return spawnGameObject(position, rotation, BalloonGameObject::new, uuid);
        }
        if(type.equals("controller")){
            return spawnGameObject(position, rotation, ControllerGameObject::new, uuid);
        }
        if(type.equals("puncher")){
            return spawnGameObject(position, rotation, PunchBoxGameObject::new, uuid);
        }
        if(type.equals("propeller")){
            return spawnGameObject(position, rotation, PropellerGameObject::new, uuid);
        }
        if(type.equals("tnt")){
            return spawnGameObject(position, rotation, TntGameObject::new, uuid);
        }
        if(type.equals("rotator")){
            return spawnGameObject(position, rotation, RotatorGameObject::new, uuid);
        }
        if(type.equals("cannon")){
            return spawnGameObject(position, rotation, CannonGameObject::new, uuid);
        }
        if(type.equals("bullet")){
            return spawnGameObject(position, rotation, BulletGameObject::new, uuid);
        }
        if(type.equals("position_sensor")){
            return spawnGameObject(position, rotation, PositionSensorGameObject::new, uuid);
        }
        throw new IllegalArgumentException("unknown type " + type);
    }
    private void addPlayer(Player player){
        this.players.add(player);
        ArrayList<Object> messages = new ArrayList<>();
        this.clientWorldManager.addPlayer(player);
        player.send(this.terrain.createMessage());
        player.sendAll(messages);
    }
    private void tick(float deltaTime) throws Exception{
        for(GameObject gameObject : this.newGameObjects){
            this.gameObjects.put(gameObject.getId(), gameObject);
        }
        this.newGameObjects.clear();
        this.gameObjects.entrySet().removeIf(entry -> {
            if(entry.getValue().isRemoved()){
                entry.getValue().destroy();
                return true;
            } else {
                return false;
            }
        });
        terrain.rebuildIfNeeded();
        if(!paused) {
            for(GameObject gameObject : this.gameObjects.values()){
                gameObject.tick();
            }
            for(int i = 0;i < 10;i++) {
                this.physics.step(deltaTime / 10, 10, 10);
            }
        }
        this.clientWorldManager.updatePositions();
        this.players.forEach(Player::tick);
        this.players.removeIf(Player::isDisconnected);
        while(this.networkServer.visitMessage(new ServerMessage.Visitor() {
            @Override
            public void connect(SocketUser user) {
                Player player = new Player(Server.this, new ServerNetXConnection(user));
                addPlayer(player);
                user.setUserData(player);
            }
            @Override
            public void disconnect(SocketUser user) {
                ((Player)user.getUserData()).disconnect();
            }
            @Override
            public void message(SocketUser user, Object msg) {
                ((ServerNetXConnection)((Player)user.getUserData()).connection).queue.add(msg);
            }
        }));
        if(tickCount%20==1){
            InetSocketAddress address = networkServer.getAddress();
            if(address != null) {
                JsonValue json = new JsonValue(JsonValue.ValueType.object);
                json.addChild("id", new JsonValue(uuid.toString()));
                json.addChild("port", new JsonValue(address.getPort()));
                try {
                    LANBroadcaster.broadcast(json.toJson(JsonWriter.OutputType.json), InetAddress.getByName("230.1.2.3"), 4321);
                } catch (Exception ignored){}
            }
        }
    }
    public SaveFile dumpToSaveFile(){
        SaveFile saveFile = new SaveFile(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        this.terrain.terrain.forEach((s, pathDS) -> {
            ArrayList<ArrayList<Vector2>> paths = new ArrayList<>();
            for(PathD path : pathDS){
                ArrayList<Vector2> realPath = new ArrayList<>();
                for(PointD pointD : path){
                    realPath.add(new Vector2((float) pointD.x, (float) pointD.y));
                }
                paths.add(realPath);
            }
            saveFile.terrain.put(s, paths);
        });
        this.gameObjects.values().forEach(gameObject -> {
            if(gameObject == gameObject.vehicle.gameObjects.get(0)){
                saveFile.savedVehicles.add(gameObject.vehicle.save());
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                gameObject.save(new DataOutputStream(outputStream));
            } catch (IOException e) {
                e.printStackTrace();
            }
            saveFile.savedGameObjects.add(new SaveFile.SavedGameObject(gameObject.getType(), gameObject.uuid, gameObject.getBaseBody().getPosition().cpy(), gameObject.getBaseBody().getAngle(), outputStream.toByteArray()));
            for(Map.Entry<String, GameObject.ConnectionData> entry : gameObject.connections.entrySet()){
                if(gameObject.getId() < entry.getValue().other.getId()){
                    saveFile.savedJoints.add(new SaveFile.SavedJoint(gameObject.uuid, entry.getKey(), entry.getValue().other.uuid, entry.getValue().otherName));
                }
            }
        });
        return saveFile;
    }
    public void createExplosion(Vector2 position, float strength){
        this.terrain.place("", position, strength*2);
        Array<Body> bodies = new Array<>();
        this.physics.getBodies(bodies);
        for(Body body : bodies){
            if(body.getUserData() instanceof BulletGameObject){
                continue;
            }
            float power = strength*4 - body.getPosition().dst(position);
            if(power > 0){
                Vector2 impulse = body.getPosition().sub(position).scl(power * 50);
                body.applyLinearImpulse(impulse, body.getWorldCenter(), true);
            }
        }
    }
    public GameObject getGameObjectByUUID(UUID uuid){
        return this.gameObjects.values().stream().filter(gameObject -> gameObject.uuid.equals(uuid)).findAny().or(() -> this.newGameObjects.stream().filter(gameObject -> gameObject.uuid.equals(uuid)).findAny()).orElse(null);
    }
    public void loadSaveFile(SaveFile saveFile){
        this.gameObjects.forEach((integer, gameObject) -> gameObject.remove());
        this.gameObjects.clear();
        this.players.forEach(Player::clearPinched);
        HashMap<UUID,byte[]> data = new HashMap<>();
        for(SaveFile.SavedGameObject gameObject : saveFile.savedGameObjects){
            spawnGameObject(gameObject.position, gameObject.rotation, gameObject.type, gameObject.id);
            data.put(gameObject.id, gameObject.data);
        }
        for(SaveFile.SavedJoint joint : saveFile.savedJoints){
            GameObject first = getGameObjectByUUID(joint.first);
            GameObject second = getGameObjectByUUID(joint.second);
            joinGameObject(first, joint.firstName, second, joint.secondName);
        }
        this.terrain.terrain.clear();
        for(Map.Entry<String, ArrayList<ArrayList<Vector2>>> entry : saveFile.terrain.entrySet()){
            PathsD paths = new PathsD();
            for(ArrayList<Vector2> pathsReal : entry.getValue()){
                PathD path = new PathD();
                for(Vector2 point : pathsReal){
                    path.add(new PointD(point.x, point.y));
                }
                paths.add(path);
            }
            this.terrain.terrain.put(entry.getKey(), paths);
        }
        data.forEach((uuid1, bytes) -> {
            try {
                getGameObjectByUUID(uuid1).load(new DataInputStream(new ByteArrayInputStream(bytes)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        saveFile.savedVehicles.forEach(vehicle -> {
            GameObject gameObject = getGameObjectByUUID(vehicle.firstGameObjectId);
            gameObject.vehicle.load(vehicle);
        });
    }
    public void joinGameObject(GameObject first, String firstName, GameObject second, String secondName){
        if(second instanceof FrameGameObject){
            GameObject tmpGameObject = first;
            first = second;
            second = tmpGameObject;
            String tmpName = firstName;
            firstName = secondName;
            secondName = tmpName;
        }
        if(!(first instanceof FrameGameObject)){
            throw new RuntimeException("one of joined must be frame");
        }
        Joint joint = second.createJoint(secondName, first, firstName);
        first.connect(firstName, second, secondName, joint);
        second.connect(secondName, first, firstName, joint);
        first.vehicle.add(second);
    }
    public void start(){
        new Thread(() -> {
            while(!stopped){
                synchronized (physics) {
                    try {
                        tick(1f / 20f);
                    } catch (Exception e) {
                        e.printStackTrace();
                        stop();
                    }
                }
                tickCount++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }
    public void stop(){
        this.stopped = true;
        this.networkServer.close();
        this.physics.dispose();
        try {
            FileOutputStream stream = new FileOutputStream("save.sbs");
            dumpToSaveFile().toStream(new DataOutputStream(stream));
            stream.close();
        } catch(IOException exception){
            System.out.println("couldn't save");
        }
    }
}
