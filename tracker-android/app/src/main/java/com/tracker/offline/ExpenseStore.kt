package com.tracker.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

data class Expense(val id: Long, val amount: Int, val category: String, val item: String, val day: String, val created: Long)

class ExpenseStore(context: Context): SQLiteOpenHelper(context, "tracker.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,amount INTEGER NOT NULL CHECK(amount>0),category TEXT NOT NULL,item TEXT NOT NULL,day TEXT NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX expenses_day_idx ON expenses(day)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(amount: Int, category: String, item: String, day: String=LocalDate.now().toString()): Long {
        require(amount>0)
        val v=ContentValues().apply {put("amount",amount);put("category",category);put("item",item);put("day",day);put("created",System.currentTimeMillis())}
        return writableDatabase.insertOrThrow("expenses",null,v)
    }
    fun update(id: Long, amount: Int, category: String, item: String) {
        require(amount>0)
        val v=ContentValues().apply {put("amount",amount);put("category",category);put("item",item)}
        writableDatabase.update("expenses",v,"id=?",arrayOf(id.toString()))
    }
    fun delete(id: Long) { writableDatabase.delete("expenses","id=?",arrayOf(id.toString())) }
    fun all(): List<Expense> {
        val out= mutableListOf<Expense>()
        readableDatabase.rawQuery("SELECT id,amount,category,item,day,created FROM expenses ORDER BY created DESC,id DESC",null).use { c ->
            while(c.moveToNext()) out+=Expense(c.getLong(0),c.getInt(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5))
        }
        return out
    }
}
