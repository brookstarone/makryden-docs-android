package com.makryden.docs.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE kind = :kind")
    fun getItemsByKind(kind: String): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertItem(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    fun deleteItemById(id: String)

    @Query("SELECT * FROM items")
    fun getAllItems(): List<ItemEntity>
}
