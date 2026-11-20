package com.example.aichat;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Store extends SQLiteOpenHelper {

    private static final String DB_NAME = "aichat.db";
    private static final int DB_VERSION = 1;
    private final Context ctx;

    public Store(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.ctx = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT," +
                "created_at INTEGER," +
                "updated_at INTEGER," +
                "model TEXT," +
                "group_id INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "conversation_id INTEGER," +
                "role TEXT," +
                "content TEXT," +
                "created_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS groups(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT," +
                "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public long newConversation() {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.execSQL("INSERT INTO conversations(title, created_at, updated_at, model, group_id) VALUES(?,?,?,?,?)",
                new Object[]{"新会话", now, now, "", -1});
        Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    public void saveMessage(long convId, String role, String content) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.execSQL("INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
                new Object[]{convId, role, content, now});
        db.execSQL("UPDATE conversations SET updated_at=? WHERE id=?", new Object[]{now, convId});
    }

    public List<String[]> loadMessages(long convId) {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id ASC",
                new String[]{String.valueOf(convId)});
        while (c.moveToNext()) {
            list.add(new String[]{c.getString(0), c.getString(1)});
        }
        c.close();
        return list;
    }

    public int countMessages(long convId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM messages WHERE conversation_id=?", new String[]{String.valueOf(convId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public List<String[]> listConversations() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, title FROM conversations ORDER BY updated_at DESC", null);
        while (c.moveToNext()) {
            list.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        return list;
    }

    public String getConversationTitle(long convId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT title FROM conversations WHERE id=?", new String[]{String.valueOf(convId)});
        String t = "";
        if (c.moveToFirst()) t = c.getString(0);
        c.close();
        return t;
    }

    public void updateTitle(long convId, String title) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE conversations SET title=?, updated_at=? WHERE id=?",
                new Object[]{title, System.currentTimeMillis(), convId});
    }

    public void deleteConversation(long convId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM messages WHERE conversation_id=?", new Object[]{convId});
        db.execSQL("DELETE FROM conversations WHERE id=?", new Object[]{convId});
    }

    public String getConversationModel(long convId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT model FROM conversations WHERE id=?", new String[]{String.valueOf(convId)});
        String m = "";
        if (c.moveToFirst()) m = c.getString(0);
        c.close();
        return m;
    }

    public void setConversationModel(long convId, String model) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE conversations SET model=? WHERE id=?", new Object[]{model, convId});
    }

    public long getLatestConversationId() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC LIMIT 1", null);
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    public long newGroup(String name) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.execSQL("INSERT INTO groups(name, created_at) VALUES(?,?)", new Object[]{name, now});
        Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    public List<String[]> listGroups() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name FROM groups ORDER BY created_at DESC", null);
        while (c.moveToNext()) {
            list.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        return list;
    }

    public void deleteGroup(long groupId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE conversations SET group_id=-1 WHERE group_id=?", new Object[]{groupId});
        db.execSQL("DELETE FROM groups WHERE id=?", new Object[]{groupId});
    }

    public List<String[]> listConversationsInGroup(long groupId) {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, title FROM conversations WHERE group_id=? ORDER BY updated_at DESC",
                new String[]{String.valueOf(groupId)});
        while (c.moveToNext()) {
            list.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        return list;
    }

    public void moveToGroup(long convId, long groupId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE conversations SET group_id=? WHERE id=?", new Object[]{groupId, convId});
    }

    public void moveOutGroup(long convId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE conversations SET group_id=-1 WHERE id=?", new Object[]{convId});
    }

    public String exportAll() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray convs = new JSONArray();
        List<String[]> list = listConversations();
        for (String[] item : list) {
            long id = Long.parseLong(item[0]);
            String title = item[1];
            JSONObject cobj = new JSONObject();
            cobj.put("id", id);
            cobj.put("title", title);
            cobj.put("model", getConversationModel(id));
            JSONArray msgs = new JSONArray();
            for (String[] m : loadMessages(id)) {
                JSONObject mo = new JSONObject();
                mo.put("role", m[0]);
                mo.put("content", m[1]);
                msgs.put(mo);
            }
            cobj.put("messages", msgs);
            convs.put(cobj);
        }
        root.put("conversations", convs);
        return root.toString();
    }

    public void importAll(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray convs = root.optJSONArray("conversations");
        if (convs == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < convs.length(); i++) {
                JSONObject cobj = convs.getJSONObject(i);
                String title = cobj.optString("title", "导入会话");
                String model = cobj.optString("model", "");
                long now = System.currentTimeMillis();
                db.execSQL("INSERT INTO conversations(title, created_at, updated_at, model, group_id) VALUES(?,?,?,?,?)",
                        new Object[]{title, now, now, model, -1});
                Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
                long newId = -1;
                if (c.moveToFirst()) newId = c.getLong(0);
                c.close();
                JSONArray msgs = cobj.optJSONArray("messages");
                if (msgs != null) {
                    for (int j = 0; j < msgs.length(); j++) {
                        JSONObject mo = msgs.getJSONObject(j);
                        db.execSQL("INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
                                new Object[]{newId, mo.optString("role", "user"), mo.optString("content", ""), now});
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
