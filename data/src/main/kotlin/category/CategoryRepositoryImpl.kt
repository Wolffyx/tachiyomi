/*
 * Copyright (C) 2018 The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package tachiyomi.data.category

import com.pushtorefresh.storio3.sqlite.StorIOSQLite
import com.pushtorefresh.storio3.sqlite.queries.Query
import com.pushtorefresh.storio3.sqlite.queries.RawQuery
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import tachiyomi.core.db.inTransaction
import tachiyomi.core.db.withId
import tachiyomi.core.db.withIds
import tachiyomi.data.category.model.MangaCategory
import tachiyomi.data.category.resolver.CategoryWithCountGetResolver
import tachiyomi.data.category.resolver.RenameCategoryPutResolver
import tachiyomi.data.category.resolver.ReorderCategoriesPutResolver
import tachiyomi.data.category.table.CategoryTable
import tachiyomi.data.category.table.MangaCategoryTable
import tachiyomi.domain.category.Category
import tachiyomi.domain.category.CategoryWithCount
import tachiyomi.domain.category.repository.CategoryRepository
import javax.inject.Inject

internal class CategoryRepositoryImpl @Inject constructor(
  private val storio: StorIOSQLite
) : CategoryRepository {

  private val categories = storio.get()
    .listOfObjects(Category::class.java)
    .withQuery(Query.builder()
      .table(CategoryTable.TABLE)
      .orderBy(CategoryTable.COL_ORDER)
      .build())
    .prepare()
    .asRxFlowable(BackpressureStrategy.LATEST)
    .replay(1)
    .autoConnect()

  override fun getCategories(): Flowable<List<Category>> {
    return categories
  }

  override fun getCategoriesWithCount(): Flowable<List<CategoryWithCount>> {
    return storio.get()
      .listOfObjects(CategoryWithCount::class.java)
      .withQuery(RawQuery.builder()
        .query(CategoryWithCountGetResolver.query)
        .observesTables(CategoryTable.TABLE, MangaCategoryTable.TABLE)
        .build())
      .withGetResolver(CategoryWithCountGetResolver)
      .prepare()
      .asRxFlowable(BackpressureStrategy.LATEST)
  }

  override fun getCategoriesForManga(mangaId: Long): Flowable<List<Category>> {
    return storio.get()
      .listOfObjects(Category::class.java)
      .withQuery(RawQuery.builder()
        .query("""
          SELECT ${CategoryTable.TABLE}.* FROM ${CategoryTable.TABLE}
          JOIN ${MangaCategoryTable.TABLE} ON ${CategoryTable.TABLE}.${CategoryTable.COL_ID} =
          ${MangaCategoryTable.TABLE}.${MangaCategoryTable.COL_CATEGORY_ID}
          WHERE ${MangaCategoryTable.COL_MANGA_ID} = ?
        """)
        .args(mangaId)
        .build())
      .prepare()
      .asRxFlowable(BackpressureStrategy.LATEST)
  }

  override fun addCategory(category: Category): Completable {
    return storio.put()
      .`object`(category)
      .prepare()
      .asRxCompletable()
  }

  override fun addCategories(categories: List<Category>): Completable {
    return storio.put()
      .objects(categories)
      .prepare()
      .asRxCompletable()
  }

  override fun createCategory(name: String, order: Int): Completable {
    val newCategory = Category(name = name, order = order)
    return storio.put()
      .`object`(newCategory)
      .prepare()
      .asRxCompletable()
  }

  override fun renameCategory(categoryId: Long, newName: String): Completable {
    val category = Category(id = categoryId, name = newName)
    return storio.put()
      .`object`(category)
      .withPutResolver(RenameCategoryPutResolver())
      .prepare()
      .asRxCompletable()
  }

  override fun reorderCategories(categories: List<Category>): Completable {
    val updatedCategories = categories.mapIndexed { index: Int, category: Category ->
      category.copy(order = index)
    }
    return storio.put()
      .objects(updatedCategories)
      .withPutResolver(ReorderCategoriesPutResolver())
      .prepare()
      .asRxCompletable()
  }

  override fun deleteCategory(categoryId: Long): Completable {
    return storio.delete()
      .withId(CategoryTable.TABLE, CategoryTable.COL_ID, categoryId)
      .prepare()
      .asRxCompletable()
  }

  override fun deleteCategories(categoryIds: Collection<Long>): Completable {
    return storio.delete()
      .withIds(CategoryTable.TABLE, CategoryTable.COL_ID, categoryIds)
      .prepare()
      .asRxCompletable()
  }

  override fun setCategoriesForMangas(
    categoryIds: Collection<Long>,
    mangaIds: Collection<Long>
  ): Completable {
    return Completable.fromAction {
      storio.inTransaction {
        deleteAllCategoriesForMangas(mangaIds)
        addCategoriesForMangas(categoryIds, mangaIds)
      }
    }
  }

  override fun deleteCategoriesForMangas(mangaIds: Collection<Long>): Completable {
    return Completable.fromAction { deleteAllCategoriesForMangas(mangaIds) }
  }

  private fun addCategoriesForMangas(categoryIds: Collection<Long>, mangaIds: Collection<Long>) {
    val mangaCategories = mangaIds.flatMap { mangaId ->
      categoryIds.map { categoryId -> MangaCategory(mangaId, categoryId) }
    }
    storio.put()
      .objects(mangaCategories)
      .prepare()
      .executeAsBlocking()
  }

  private fun deleteAllCategoriesForMangas(mangaIds: Collection<Long>) {
    storio.delete()
      .withIds(MangaCategoryTable.TABLE, MangaCategoryTable.COL_MANGA_ID, mangaIds)
      .prepare()
      .executeAsBlocking()
  }
}