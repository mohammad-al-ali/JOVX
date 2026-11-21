package com.jovx.api.catalog.repository;

import com.jovx.api.catalog.model.CatalogModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Catalog-owned tables only; store and partner facts come through partners::api. */
@Repository
@Profile("local-postgres")
public final class CatalogRepository {
  private static final String PRODUCT_COLUMNS =
      "id,request_id,store_id,partner_id,category_id,name,description,price_amount,currency,member_price_amount,"
          + "points,stock_tracked,stock,review_status,review_reason,published,version,created_at,updated_at";
  private final JdbcTemplate jdbc;

  public CatalogRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  // ---- categories ----

  public List<Category> categories(boolean activeOnly) {
    return jdbc.query("SELECT id,name_ar,sort_order,active FROM product_categories"
        + (activeOnly ? " WHERE active" : "") + " ORDER BY sort_order,name_ar", CATEGORY);
  }

  public Optional<Category> category(UUID id) {
    return first(jdbc.query("SELECT id,name_ar,sort_order,active FROM product_categories WHERE id=?", CATEGORY, id.toString()));
  }

  public void insertCategory(Category c) {
    jdbc.update("INSERT INTO product_categories(id,name_ar,sort_order,active) VALUES (?,?,?,?)",
        c.id().toString(), c.name(), c.sortOrder(), c.active());
  }

  public boolean updateCategory(Category c) {
    return jdbc.update("UPDATE product_categories SET name_ar=?,sort_order=?,active=? WHERE id=?",
        c.name(), c.sortOrder(), c.active(), c.id().toString()) == 1;
  }

  // ---- products ----

  public void insertProduct(Product p, UUID actor) {
    jdbc.update("INSERT INTO products(" + PRODUCT_COLUMNS + ",created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        p.id().toString(), p.requestId().toString(), p.storeId().toString(), p.partnerId().toString(),
        p.categoryId().toString(), p.name(), p.description(), p.priceAmount(), p.currency(), p.memberPriceAmount(),
        p.points(), p.stockTracked(), p.stock(), p.reviewStatus().name(), p.reviewReason(), p.published(),
        p.version(), ts(p.createdAt()), ts(p.updatedAt()), actor.toString());
  }

  public Optional<Product> byRequest(UUID requestId) {
    return first(jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE request_id=?", PRODUCT, requestId.toString()));
  }

  public Optional<Product> lock(UUID id) {
    return first(jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE id=? AND deleted_at IS NULL FOR UPDATE",
        PRODUCT, id.toString()));
  }

  public Optional<Product> product(UUID id) {
    return first(jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE id=? AND deleted_at IS NULL", PRODUCT, id.toString()));
  }

  public List<Product> storeProducts(UUID store) {
    return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE store_id=? AND deleted_at IS NULL ORDER BY name,id",
        PRODUCT, store.toString());
  }

  /**
   * Visible products (approved, published, not deleted) of the listed stores. Member price, filters,
   * order and the total are computed in one query; the store set and each partner minimum come from
   * partners::api, so no other module's table is read here.
   */
  public SearchPage search(List<UUID> stores, List<java.math.BigDecimal> minimums, ProductSearch q,
      Collection<UUID> storesMatchingText) {
    var args = new ArrayList<Object>();
    args.add(stores.stream().map(UUID::toString).toArray(String[]::new));
    args.add(minimums.toArray(java.math.BigDecimal[]::new));
    var where = new StringBuilder(" WHERE p.deleted_at IS NULL AND p.review_status='APPROVED' AND p.published");
    if (q.categoryId() != null) { where.append(" AND p.category_id=?"); args.add(q.categoryId().toString()); }
    if (q.inStockOnly()) where.append(" AND NOT (p.stock_tracked AND COALESCE(p.stock,0)=0)");
    if (!q.text().isEmpty()) {
      String like = "%" + q.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      where.append(" AND (jovx_ar_norm(p.name) LIKE ? OR jovx_ar_norm(p.description) LIKE ?");
      args.add(like); args.add(like);
      if (!storesMatchingText.isEmpty()) {
        where.append(" OR p.store_id::text = ANY(?)");
        args.add(storesMatchingText.stream().map(UUID::toString).toArray(String[]::new));
      }
      where.append(")");
    }
    var outer = new StringBuilder(" WHERE TRUE");
    if (q.minPrice() != null) { outer.append(" AND member_price>=?"); args.add(q.minPrice()); }
    if (q.maxPrice() != null) { outer.append(" AND member_price<=?"); args.add(q.maxPrice()); }
    String order = switch (q.sort()) {
      case NEWEST -> "created_at DESC";
      case PRICE_ASC -> "member_price ASC";
      case PRICE_DESC -> "member_price DESC";
      case SAVING_DESC -> "price_amount-member_price DESC";
      case POINTS_DESC -> "points DESC";
      case NAME -> "name ASC";
    };
    args.add(q.limit()); args.add(q.offset());
    String sql = "WITH s(store_id,minimum) AS (SELECT * FROM unnest(?::text[],?::numeric[]))"
        + " SELECT " + PRODUCT_COLUMNS + ",member_price,COUNT(*) OVER() FROM (SELECT p.*,"
        + " COALESCE(p.member_price_amount,round(p.price_amount*(100-s.minimum)/100)::bigint) AS member_price"
        + " FROM products p JOIN s ON s.store_id=p.store_id::text" + where + ") x" + outer
        + " ORDER BY " + order + ",id LIMIT ? OFFSET ?";
    long[] total = {0};
    var hits = jdbc.query(sql, (r, i) -> {
      total[0] = r.getLong(21);
      return new SearchHit(PRODUCT.mapRow(r, i), r.getLong(20));
    }, args.toArray());
    return new SearchPage(total[0], hits);
  }

  public List<Product> reviewQueue(ReviewStatus status, int limit) {
    return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM products WHERE deleted_at IS NULL"
        + (status == null ? "" : " AND review_status=?") + " ORDER BY updated_at LIMIT ?",
        PRODUCT, status == null ? new Object[] {limit} : new Object[] {status.name(), limit});
  }

  /** Writes the partner's edit; the version check is done by the caller under the row lock. */
  public void updateDetails(UUID id, ProductDraft d, ReviewStatus status, String reason, Instant now) {
    jdbc.update("UPDATE products SET category_id=?,name=?,description=?,price_amount=?,member_price_amount=?,points=?,"
            + "stock_tracked=?,stock=?,review_status=?,review_reason=?,version=version+1,updated_at=? WHERE id=?",
        d.categoryId().toString(), d.name(), d.description(), d.priceAmount(), d.memberPriceAmount(), d.points(),
        d.stockTracked(), d.stock(), status.name(), reason, ts(now), id.toString());
  }

  public void updateStock(UUID id, Integer stock, Instant now) {
    jdbc.update("UPDATE products SET stock=?,version=version+1,updated_at=? WHERE id=?", stock, ts(now), id.toString());
  }

  public void updatePublished(UUID id, boolean published, Instant now) {
    jdbc.update("UPDATE products SET published=?,version=version+1,updated_at=? WHERE id=?", published, ts(now), id.toString());
  }

  public void review(UUID id, ReviewStatus status, String reason, UUID actor, Instant now) {
    jdbc.update("UPDATE products SET review_status=?,review_reason=?,reviewed_by=?,reviewed_at=?,version=version+1 WHERE id=?",
        status.name(), reason, actor.toString(), ts(now), id.toString());
  }

  /** Images count as product data (decision 24): any change returns the product to review. */
  public void backToReview(UUID id, Instant now) {
    jdbc.update("UPDATE products SET review_status='PENDING',review_reason=NULL,version=version+1,updated_at=? WHERE id=?",
        ts(now), id.toString());
  }

  public void softDelete(UUID id, Instant now) {
    jdbc.update("UPDATE products SET deleted_at=?,updated_at=? WHERE id=?", ts(now), ts(now), id.toString());
  }

  // ---- images ----

  public List<ProductImage> images(UUID product) {
    return jdbc.query("SELECT id,product_id,position,object_key,content_type,size_bytes FROM product_images"
        + " WHERE product_id=? ORDER BY position", IMAGE, product.toString());
  }

  public Map<UUID, List<ProductImage>> images(Collection<UUID> products) {
    var result = new HashMap<UUID, List<ProductImage>>();
    if (products.isEmpty()) return result;
    var keys = products.stream().map(UUID::toString).toList();
    String marks = String.join(",", Collections.nCopies(keys.size(), "?"));
    for (var i : jdbc.query("SELECT id,product_id,position,object_key,content_type,size_bytes FROM product_images"
        + " WHERE product_id IN (" + marks + ") ORDER BY position", IMAGE, keys.toArray()))
      result.computeIfAbsent(i.productId(), k -> new ArrayList<>()).add(i);
    return result;
  }

  public void insertImage(ProductImage i, Instant now) {
    jdbc.update("INSERT INTO product_images(id,product_id,position,object_key,content_type,size_bytes,created_at) VALUES (?,?,?,?,?,?,?)",
        i.id().toString(), i.productId().toString(), i.position(), i.objectKey(), i.contentType(), i.sizeBytes(), ts(now));
  }

  /** Removes one image and closes the gap so position 0 stays the cover. */
  public Optional<ProductImage> deleteImage(UUID product, UUID image) {
    var found = first(jdbc.query("SELECT id,product_id,position,object_key,content_type,size_bytes FROM product_images"
        + " WHERE id=? AND product_id=?", IMAGE, image.toString(), product.toString()));
    found.ifPresent(i -> {
      jdbc.update("DELETE FROM product_images WHERE id=?", i.id().toString());
      for (var later : images(product))
        if (later.position() > i.position())
          jdbc.update("UPDATE product_images SET position=? WHERE id=?", later.position() - 1, later.id().toString());
    });
    return found;
  }

  public void event(UUID product, UUID actor, String action, String reason, Instant now) {
    jdbc.update("INSERT INTO product_events(id,product_id,actor_id,action,reason,created_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID().toString(), product.toString(), actor.toString(), action, reason, ts(now));
  }

  // ---- mapping ----

  private static final RowMapper<Category> CATEGORY = (r, i) -> new Category(uuid(r, 1), r.getString(2), r.getInt(3), r.getBoolean(4));
  private static final RowMapper<ProductImage> IMAGE = (r, i) -> new ProductImage(uuid(r, 1), uuid(r, 2), r.getInt(3),
      r.getString(4), r.getString(5), r.getInt(6));
  private static final RowMapper<Product> PRODUCT = (r, i) -> new Product(uuid(r, 1), uuid(r, 2), uuid(r, 3), uuid(r, 4),
      uuid(r, 5), r.getString(6), r.getString(7), r.getLong(8), r.getString(9),
      r.getObject(10) == null ? null : r.getLong(10), r.getLong(11), r.getBoolean(12),
      r.getObject(13) == null ? null : r.getInt(13), ReviewStatus.valueOf(r.getString(14)), r.getString(15),
      r.getBoolean(16), r.getInt(17), r.getTimestamp(18).toInstant(), r.getTimestamp(19).toInstant());

  private static UUID uuid(ResultSet r, int i) throws SQLException {
    String v = r.getString(i);
    return v == null ? null : UUID.fromString(v.strip());
  }

  private static Timestamp ts(Instant v) {
    return v == null ? null : Timestamp.from(v);
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }
}
