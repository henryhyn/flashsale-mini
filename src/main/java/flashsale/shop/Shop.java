package flashsale.shop;

/** 商户详情。avgPrice 是人均价格 (分)，score 是评分 × 10 */
public record Shop(long id, String name, String address, int avgPrice, int score) { }
