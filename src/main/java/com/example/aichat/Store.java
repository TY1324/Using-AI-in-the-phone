package com.example.aichat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Store {

    private static final String DB_NAME = "aichat.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_CONV = "conversations";
    private static final String TABLE_MSG = "messages";
    private static final String TABLE_GROUP = "groups";

    private final SQLiteOpenHelper helper;

    public Store(Context ctx) {
        helper = new SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CONV + "("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "title TEXT,"
                        + "created_at INTEGER,"
                        + "updated_at INTEGER,"
                        + "model TEXT,"
                        + "group_id INTEGER DEFAULT 0"
                        + ")");
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MSG + "("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "conversation_id INTEGER,"
                        + "role TEXT,"
                        + "content TEXT,"
                        + "created_at INTEGER"
                        + ")");
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + "("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "name TEXT,"
                        + "created_at INTEGER"
                        + ")");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                // 当前版本无需升级逻辑
            }
        };
    }

    // ===== 会话 =====

    public long newConversation() {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        long now = System.currentTimeMillis();
        cv.put("title", "新会话");
        cv.put("created_at", now);
        cv.put("updated_at", now);
        cv.put("model", "");
        cv.put("group_id", 0);
        long id = db.insert(TABLE_CONV, null, cv);
        db.close();
        return id;
    }

    public long getLatestConversationId() {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_CONV, new String[]{"id"}, null, null,
                null, null, "updated_at DESC", "1");
        long id = -1;
        if (c.moveToFirst()) {
            id = c.getLong(0);
        }
        c.close();
        db.close();
        return id;
    }

    public String getConversationTitle(long convId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_CONV, new String[]{"title"}, "id=?",
                new String[]{String.valueOf(convId)}, null, null, null);
        String title = "";
        if (c.moveToFirst()) {
            title = c.getString(0);
        }
        c.close();
        db.close();
        return title;
    }

    public void updateTitle(long convId, String title) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONV, cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public void setConversationModel(long convId, String model) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("model", model == null ? "" : model);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONV, cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public String getConversationModel(long convId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_CONV, new String[]{"model"}, "id=?",
                new String[]{String.valueOf(convId)}, null, null, null);
        String model = "";
        if (c.moveToFirst()) {
            model = c.getString(0);
            if (model == null) model = "";
        }
        c.close();
        db.close();
        return model;
    }

    public void deleteConversation(long convId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE_MSG, "conversation_id=?", new String[]{String.valueOf(convId)});
        db.delete(TABLE_CONV, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public List<String[]> listConversations() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_CONV, new String[]{"id", "title", "updated_at"},
                null, null, null, null, "updated_at DESC");
        while (c.moveToNext()) {
            list.add(new String[]{
                    String.valueOf(c.getLong(0)),
                    c.getString(1),
                    String.valueOf(c.getLong(2))
            });
        }
        c.close();
        db.close();
        return list;
    }

    public int countMessages(long convId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_MSG, new String[]{"count(*)"}, "conversation_id=?",
                new String[]{String.valueOf(convId)}, null, null, null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        db.close();
        return count;
    }

    // ===== 消息 =====

    public void saveMessage(long convId, String role, String content) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", convId);
        cv.put("role", role);
        cv.put("content", content);
        cv.put("created_at", System.currentTimeMillis());
        db.insert(TABLE_MSG, null, cv);
        ContentValues upd = new ContentValues();
        upd.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONV, upd, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public List<String[]> loadMessages(long convId) {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_MSG, new String[]{"role", "content"},
                "conversation_id=?", new String[]{String.valueOf(convId)},
                null, null, "created_at ASC");
        while (c.moveToNext()) {
            list.add(new String[]{c.getString(0), c.getString(1)});
        }
        c.close();
        db.close();
        return list;
    }

    // ===== 项目组 =====

    public long newGroup(String name) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created_at", System.currentTimeMillis());
        long id = db.insert(TABLE_GROUP, null, cv);
        db.close();
        return id;
    }

    public List<String[]> listGroups() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_GROUP, new String[]{"id", "name"},
                null, null, null, null, "created_at DESC");
        while (c.moveToNext()) {
            list.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        db.close();
        return list;
    }

    public List<String[]> listConversationsInGroup(long groupId) {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(TABLE_CONV, new String[]{"id", "title", "updated_at"},
                "group_id=?", new String[]{String.valueOf(groupId)},
                null, null, "updated_at DESC");
        while (c.moveToNext()) {
            list.add(new String[]{
                    String.valueOf(c.getLong(0)),
                    c.getString(1),
                    String.valueOf(c.getLong(2))
            });
        }
        c.close();
        db.close();
        return list;
    }

    public void moveToGroup(long convId, long groupId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("group_id", groupId);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONV, cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public void moveOutGroup(long convId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("group_id", 0);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONV, cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public void deleteGroup(long groupId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE_GROUP, "id=?", new String[]{String.valueOf(groupId)});
        // 将组内会话移出组
        ContentValues cv = new ContentValues();
        cv.put("group_id", 0);
        db.update(TABLE_CONV, cv, "group_id=?", new String[]{String.valueOf(groupId)});
        db.close();
    }

    // ===== 备份 / 恢复 =====

    public String exportAll() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray convs = new JSONArray();
        List<String[]> allConv = listConversations();
        for (String[] c : allConv) {
            long id = Long.parseLong(c[0]);
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("title", c[1]);
            obj.put("updated_at", c[2]);
            JSONArray msgs = new JSONArray();
            for (String[] m : loadMessages(id)) {
                JSONObject mo = new JSONObject();
                mo.put("role", m[0]);
                mo.put("content", m[1]);
                msgs.put(mo);
            }
            obj.put("messages", msgs);
            convs.put(obj);
        }
        root.put("conversations", convs);
        return root.toString();
    }

    public void importAll(String json) throws Exception {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_MSG, null, null);
            db.delete(TABLE_CONV, null, null);
            JSONObject root = new JSONObject(json);
            JSONArray convs = root.getJSONArray("conversations");
            for (int i = 0; i < convs.length(); i++) {
                JSONObject c = convs.getJSONObject(i);
                ContentValues cv = new ContentValues();
                cv.put("title", c.optString("title", "导入会话"));
                cv.put("created_at", System.currentTimeMillis());
                cv.put("updated_at", System.currentTimeMillis());
                cv.put("model", "");
                cv.put("group_id", 0);
                long convId = db.insert(TABLE_CONV, null, cv);
                JSONArray msgs = c.getJSONArray("messages");
                for (int j = 0; j < msgs.length(); j++) {
                    JSONObject m = msgs.getJSONObject(j);
                    ContentValues mv = new ContentValues();
                    mv.put("conversation_id", convId);
                    mv.put("role", m.optString("role", "user"));
                    mv.put("content", m.optString("content", ""));
                    mv.put("created_at", System.currentTimeMillis());
                    db.insert(TABLE_MSG, null, mv);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        db.close();
    }
}
