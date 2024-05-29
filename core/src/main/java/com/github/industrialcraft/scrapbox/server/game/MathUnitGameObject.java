package com.github.industrialcraft.scrapbox.server.game;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.physics.box2d.joints.WeldJointDef;
import com.github.industrialcraft.scrapbox.common.editui.*;
import com.github.industrialcraft.scrapbox.server.GameObject;
import com.github.industrialcraft.scrapbox.server.Server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class MathUnitGameObject extends GameObject {
    public static final String[] OPERATION_LIST;
    static{
        OPERATION_LIST = new String[]{"+","-","*","/"};
    }

    private ArrayList<Integer> operations;
    public MathUnitGameObject(Vector2 position, float rotation, Server server) {
        super(position, rotation, server);

        this.operations = new ArrayList<>();

        BodyDef bodyDef = new BodyDef();
        bodyDef.position.set(position);
        bodyDef.angle = rotation;
        bodyDef.type = BodyDef.BodyType.DynamicBody;
        Body base = server.physics.createBody(bodyDef);
        FixtureDef fixtureDef = new FixtureDef();
        PolygonShape shape = new PolygonShape();
        shape.setAsBox(FrameGameObject.INSIDE_SIZE, FrameGameObject.INSIDE_SIZE);
        fixtureDef.shape = shape;
        fixtureDef.density = 1F;
        base.createFixture(fixtureDef);
        this.setBody("base", "math_unit", base);
    }
    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void load(DataInputStream stream) throws IOException {
        super.load(stream);
        int count = stream.readInt();
        for(int i = 0;i < count;i++)
            operations.add(stream.readInt());
    }

    @Override
    public void save(DataOutputStream stream) throws IOException {
        super.save(stream);
        stream.writeInt(operations.size());
        for (int operation : operations) stream.writeInt(operation);
    }

    @Override
    public ArrayList<EditorUIRow> createEditorUI() {
        ArrayList<EditorUIRow> rows = new ArrayList<>();

        ArrayList<String> calibrationSelection = new ArrayList<>(List.of(OPERATION_LIST));
        for(int i = 0;i < operations.size();i++){
            ArrayList<EditorUIElement> row = new ArrayList<>();
            row.add(new EditorUILink(i*2, true, defaultValues.getOrDefault(i*2, 0f), isInputFilled(i*2)));
            row.add(new EditorUIDropDown("op"+i, calibrationSelection, operations.get(i)));
            row.add(new EditorUILink(i*2 + 1, true, defaultValues.getOrDefault(i*2+1, 0f), isInputFilled(i*2 + 1)));
            row.add(new EditorUILabel("="));
            row.add(new EditorUILink(i, false, 0f, false));
            row.add(new EditorUIButton("X", "close"+i));
            rows.add(new EditorUIRow(row));
        }
        rows.add(new EditorUIRow(new ArrayList<>(List.of(new EditorUIButton("New", "new")))));
        return rows;
    }
    @Override
    public void handleEditorUIInput(String elementId, String value) {
        super.handleEditorUIInput(elementId, value);
        if(elementId.startsWith("op")){
            int i = Integer.parseInt(elementId.replace("op", ""));
            operations.set(i, Integer.parseInt(value));
        }
        if(elementId.startsWith("close")){
            int i = Integer.parseInt(elementId.replace("close", ""));
            operations.remove(i);
            for(int j = i;j < operations.size();j++){
                valueConnections.put(j*2, valueConnections.get((j+1)*2));
                valueConnections.put(j*2+1, valueConnections.get((j+1)*2+1));
            }
            destroyValueConnection(operations.size()*2);
            destroyValueConnection(operations.size()*2+1);
        }
        if(elementId.equals("new")){
            operations.add(0);
        }
    }

    @Override
    public float getValueOnOutput(int id) {
        float first = getValueOnInput(id*2);
        float second = getValueOnInput(id*2+1);
        int op = operations.get(id);
        switch (op){
            case 0:
                return first+second;
            case 1:
                return first-second;
            case 2:
                return first*second;
            case 3:
                return first/second;
        }
        throw new IllegalArgumentException("invalid operation");
    }

    @Override
    public Joint createJoint(String thisName, GameObject other, String otherName) {
        float rotationOffset = (float) (Math.round((other.getBaseBody().getAngle()-this.getBaseBody().getAngle())/HALF_PI)*HALF_PI);
        Transform transform = other.getBaseBody().getTransform();
        this.getBaseBody().setTransform(transform.getPosition(), transform.getRotation()-rotationOffset);
        WeldJointDef joint = new WeldJointDef();
        joint.bodyA = this.getBaseBody();
        joint.bodyB = other.getBaseBody();
        joint.localAnchorA.set(new Vector2(0, 0));
        joint.localAnchorB.set(new Vector2(0, 0));
        joint.referenceAngle = rotationOffset;
        return this.server.physics.createJoint(joint);
    }

    @Override
    public HashMap<String, ConnectionEdge> getConnectionEdges() {
        HashMap<String, ConnectionEdge> edges = new HashMap<>();
        edges.put("center", new ConnectionEdge(new Vector2(0, 0), true));
        return edges;
    }

    @Override
    public String getType() {
        return "math_unit";
    }
}
