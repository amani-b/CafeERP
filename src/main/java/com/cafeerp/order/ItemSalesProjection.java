package com.cafeerp.order;

/**
 * Projection for top-selling items query results.
 */
public interface ItemSalesProjection {
    String getItemName();
    Long getTotalQuantity();
}