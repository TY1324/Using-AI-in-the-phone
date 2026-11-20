package com.example.aichat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class Store extends SQLiteOpenHelper {

    private static final String DB_NAME = "chat.db";
    private static final int DB_VERSION = 2;

    public Store(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "created_at INTEGER,"
                + "updated_at INTEGER,"
                + "model TEXT,"
                + "group_id INTEGER DEFAULT -1)");
        db.execSQL("CREATE TABLE messages("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id INTEGER,"
                + "role TEXT,"
                + "content TEXT,"
                + "created_at INTEGER)");
        db.execSQL("CREATE TABLE groups("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT,"
                + "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN model TEXT");
            db.execSQL("ALTER TABLE conversations ADD COLUMN group_id INTEGER DEFAULT -1");
            db.execSQL("CREATE TABLE groups("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT,"
                    + "created_at INTEGER)");
        }
    }

    // ===== 会话 =====

    public long newConversation() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", "新会话");
        cv.put("created_at", System.currentTimeMillis());
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("group_id", -1);
        long id = db.insert("conversations", null, cv);
        db.close();
        return id;
    }

    public long getLatestConversationId() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC LIMIT 1", null);
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        db.close();
        return id;
    }

    public List<String[]> listConversations() {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, title FROM conversations WHERE group_id=-1 ORDER BY updated_at DESC", null);
        while (c.moveToNext()) {
            result.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public List<String[]> listConversationsInGroup(long groupId) {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, title FROM conversations WHERE group_id=? ORDER BY updated_at DESC",
                new String[]{String.valueOf(groupId)});
        while (c.moveToNext()) {
            result.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public String getConversationTitle(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT title FROM conversations WHERE id=?",
                new String[]{String.valueOf(id)});
        String t = "";
        if (c.moveToFirst()) t = c.getString(0);
        c.close();
        db.close();
        return t;
    }

    public String getConversationModel(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT model FROM conversations WHERE id=?",
                new String[]{String.valueOf(id)});
        String m = null;
        if (c.moveToFirst()) m = c.getString(0);
        c.close();
        db.close();
        return m;
    }

    public void setConversationModel(long id, String model) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("model", model);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public void updateTitle(long convId, String title) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public void deleteConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "conversation_id=?", new String[]{String.valueOf(id)});
        db.delete("conversations", "id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    // ===== 项目组 =====

    public long newGroup(String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created_at", System.currentTimeMillis());
        long id = db.insert("groups", null, cv);
        db.close();
        return id;
    }

    public List<String[]> listGroups() {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name FROM groups ORDER BY id DESC", null);
        while (c.moveToNext()) {
            result.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public void deleteGroup(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("group_id", -1);
        db.update("conversations", cv, "group_id=?", new String[]{String.valueOf(id)});
        db.delete("groups", "id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public void moveToGroup(long convId, long groupId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("group_id", groupId);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public void moveOutGroup(long convId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("group_id", -1);
        db.update("conversations", cv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    // ===== 消息 =====

    public void saveMessage(long convId, String role, String content) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", convId);
        cv.put("role", role);
        cv.put("content", content);
        cv.put("created_at", System.currentTimeMillis());
        db.insert("messages", null, cv);
        ContentValues uv = new ContentValues();
        uv.put("updated_at", System.currentTimeMillis());
        db.update("conversations", uv, "id=?", new String[]{String.valueOf(convId)});
        db.close();
    }

    public List<String[]> loadMessages(long convId) {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id ASC",
                new String[]{String.valueOf(convId)});
        while (c.moveToNext()) {
            result.add(new String[]{c.getString(0), c.getString(1)});
        }
        c.close();
        db.close();
        return result;
    }

    public int countMessages(long convId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM messages WHERE conversation_id=?",
                new String[]{String.valueOf(convId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.close();
        return n;
    }
}
