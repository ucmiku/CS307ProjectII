package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.dto.ReviewRecord;
import io.sustc.service.RecipeService;
import io.sustc.service.ReviewService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReviewServiceImpl implements ReviewService {


    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private RecipeService recipeService;

    @Override
    @Transactional
    public long addReview(AuthInfo auth, long recipeId, int rating, String review) {
        // 检查参数
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        // 检查用户是否有效且活跃
        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        // 检查食谱是否存在且未被删除
        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        // 生成新的ReviewId - 使用数据库序列生成
        Long newReviewId;
        try {
            newReviewId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(ReviewId), 0) + 1 FROM reviews",
                    Long.class
            );
        } catch (Exception e) {
            newReviewId = 1L;
        }
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());

        // 插入新的评论
        jdbcTemplate.update(
                "INSERT INTO reviews (ReviewId, RecipeId, AuthorId, Rating, Review, DateSubmitted, DateModified) VALUES (?, ?, ?, ?, ?, ?, ?)",
                newReviewId,
                recipeId,
                userId,
                rating,
                review,
                now,
                now
        );

        // 刷新食谱的聚合评分
        refreshRecipeAggregatedRating(recipeId);

        return newReviewId;
    }

    @Override
    @Transactional
    public void editReview(AuthInfo auth, long recipeId, long reviewId, int rating, String review) {
        // 检查参数
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        // 检查用户是否有效且活跃
        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        // 检查评论是否存在，并且属于指定的食谱
        Long actualRecipeId;
        Long reviewAuthorId;
        try {
            actualRecipeId = jdbcTemplate.queryForObject(
                    "SELECT RecipeId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
            reviewAuthorId = jdbcTemplate.queryForObject(
                    "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        if (actualRecipeId == null || actualRecipeId != recipeId) {
            throw new IllegalArgumentException("Review does not belong to the specified recipe");
        }

        // 检查当前用户是否是评论的作者
        if (reviewAuthorId != userId) {
            throw new SecurityException("Only the review author can edit the review");
        }

        // 更新评论
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        jdbcTemplate.update(
                "UPDATE reviews SET Rating = ?, Review = ?, DateModified = ? WHERE ReviewId = ?",
                rating,
                review,
                now,
                reviewId
        );

        // 刷新食谱的聚合评分
        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public void deleteReview(AuthInfo auth, long recipeId, long reviewId) {
        // 检查参数
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 检查用户是否有效且活跃
        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        // 检查评论是否存在，并且属于指定的食谱
        Long actualRecipeId;
        Long reviewAuthorId;
        try {
            actualRecipeId = jdbcTemplate.queryForObject(
                    "SELECT RecipeId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
            reviewAuthorId = jdbcTemplate.queryForObject(
                    "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        if (actualRecipeId == null || actualRecipeId != recipeId) {
            throw new IllegalArgumentException("Review does not belong to the specified recipe");
        }

        // 检查当前用户是否是评论的作者
        if (reviewAuthorId != userId) {
            throw new SecurityException("Only the review author can delete the review");
        }

        // 删除评论的所有点赞
        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId = ?",
                reviewId
        );

        // 删除评论
        jdbcTemplate.update(
                "DELETE FROM reviews WHERE ReviewId = ?",
                reviewId
        );

        // 刷新食谱的聚合评分
        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public long likeReview(AuthInfo auth, long reviewId) {
        // 检查参数
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 验证用户认证（使用 UserService.login 验证密码）
        long userId = auth.getAuthorId();
        long loginResult = userService.login(auth);
        if (loginResult == -1L) {
            throw new SecurityException("Authentication failed");
        }

        // 检查评论是否存在
        Long reviewAuthorId;
        try {
            reviewAuthorId = jdbcTemplate.queryForObject(
                    "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        // 检查用户是否试图给自己的评论点赞
        if (reviewAuthorId != null && reviewAuthorId == userId) {
            throw new SecurityException("Users cannot like their own reviews");
        }

        // 检查是否已经点过赞
        Boolean alreadyLiked = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_likes WHERE ReviewId = ? AND AuthorId = ?)",
                Boolean.class,
                reviewId,
                userId
        );

        if (alreadyLiked != null && alreadyLiked) {
            // 已经点过赞，返回当前点赞数（no-op）
            Long likeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
            return likeCount != null ? likeCount : 0L;
        }

        // 添加点赞
        jdbcTemplate.update(
                "INSERT INTO review_likes (ReviewId, AuthorId) VALUES (?, ?)",
                reviewId,
                userId
        );

        // 获取并返回点赞总数
        Long likeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                Long.class,
                reviewId
        );

        return likeCount != null ? likeCount : 0L;
    }

    @Override
    @Transactional
    public long unlikeReview(AuthInfo auth, long reviewId) {
        // 检查参数
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 验证用户认证（使用 UserService.login 验证密码）
        long userId = auth.getAuthorId();
        long loginResult = userService.login(auth);
        if (loginResult == -1L) {
            throw new SecurityException("Authentication failed");
        }

        // 检查评论是否存在
        Boolean reviewExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reviews WHERE ReviewId = ?)",
                Boolean.class,
                reviewId
        );

        if (reviewExists == null || !reviewExists) {
            throw new IllegalArgumentException("Review does not exist");
        }

        // 检查是否已经点过赞
        Boolean hasLiked = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_likes WHERE ReviewId = ? AND AuthorId = ?)",
                Boolean.class,
                reviewId,
                userId
        );

        if (hasLiked == null || !hasLiked) {
            // 没有点过赞，返回当前点赞数（no-op）
            Long likeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
            return likeCount != null ? likeCount : 0L;
        }

        // 删除点赞
        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId = ? AND AuthorId = ?",
                reviewId,
                userId
        );

        // 获取并返回点赞总数
        Long likeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                Long.class,
                reviewId
        );

        return likeCount != null ? likeCount : 0L;
    }

    private final RowMapper<ReviewRecord> reviewRowMapper = (rs, rowNum) -> {
        ReviewRecord record = new ReviewRecord();
        record.setReviewId(rs.getLong("ReviewId"));
        record.setRecipeId(rs.getLong("RecipeId"));
        record.setAuthorId(rs.getLong("AuthorId"));
        record.setAuthorName(rs.getString("AuthorName"));
        // Handle potential BigDecimal to Float conversion for PostgresSQL
        Object ratingObj = rs.getObject("Rating");
        record.setRating(ratingObj != null ? ((Number) ratingObj).floatValue() : 0.0f);
        record.setReview(rs.getString("Review"));
        record.setDateSubmitted(rs.getTimestamp("DateSubmitted"));
        record.setDateModified(rs.getTimestamp("DateModified"));

        // 获取点赞数
        long likeCount = rs.getLong("LikeCount");
        // 创建一个长度为likeCount的数组，这里简化处理，实际项目中可能需要查询具体的点赞用户ID
        long[] likes = new long[(int) likeCount];
        for (int i = 0; i < likeCount; i++) {
            likes[i] = i; // 占位符，实际项目中应该填充真实的用户ID
        }
        record.setLikes(likes);

        return record;
    };

    @Override
    public PageResult<ReviewRecord> listByRecipe(long recipeId, int page, int size, String sort) {
        // 检查分页参数
        if (page < 1) {
            throw new IllegalArgumentException("Page must be greater than 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }

        // 检查食谱是否存在
        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        // 构建排序子句
        String orderBy = switch (sort == null ? "" : sort) {
            case "date_desc" -> " ORDER BY r.DateModified DESC ";
            case "likes_desc" -> " ORDER BY LikeCount DESC ";
            default -> " ORDER BY r.DateModified DESC "; // 默认按时间倒序
        };

        // 查询总数（不过滤已删除用户的评论）
        String countSql = "SELECT COUNT(*) FROM reviews r WHERE r.RecipeId = ? AND r.Review IS NOT NULL";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, recipeId);
        if (total == null) total = 0L;

        // 查询数据（移除 u.IsDeleted 的过滤条件，允许显示已删除用户的评论）
        String sql = "SELECT r.*, COALESCE(rl.LikeCount, 0) AS LikeCount, u.AuthorName " +
                "FROM reviews r " +
                "LEFT JOIN (SELECT ReviewId, COUNT(*) AS LikeCount FROM review_likes GROUP BY ReviewId) rl ON r.ReviewId = rl.ReviewId " +
                "LEFT JOIN users u ON r.AuthorId = u.AuthorId " +
                "WHERE r.RecipeId = ? AND r.Review IS NOT NULL " +
                orderBy +
                "LIMIT ? OFFSET ?";

        int offset = (page - 1) * size;
        List<ReviewRecord> items = jdbcTemplate.query(sql, reviewRowMapper, recipeId, size, offset);

        return PageResult.<ReviewRecord>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    @Override
    @Transactional
    public RecipeRecord refreshRecipeAggregatedRating(long recipeId) {
        // 检查食谱是否存在
        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        // 计算新的聚合评分和评论数量
        String sql = "SELECT AVG(Rating) as avgRating, COUNT(*) as reviewCount FROM reviews WHERE RecipeId = ? AND Review IS NOT NULL";
        Map<String, Object> result = jdbcTemplate.queryForMap(sql, recipeId);

        // Handle BigDecimal to Double conversion for PostgreSQL
        Object avgRatingObj = result.get("avgrating");
        Double avgRating = avgRatingObj != null ? ((Number) avgRatingObj).doubleValue() : null;
        Integer reviewCount = ((Long) result.get("reviewcount")).intValue();

        // 更新食谱表中的聚合评分和评论数量
        if (reviewCount > 0 && avgRating != null) {
            // 四舍五入到两位小数
            double roundedRating = Math.round(avgRating * 100.0) / 100.0;
            jdbcTemplate.update(
                    "UPDATE recipes SET AggregatedRating = ?, ReviewCount = ? WHERE RecipeId = ?",
                    roundedRating,
                    reviewCount,
                    recipeId
            );
        } else {
            // 如果没有评论，将聚合评分设为null，评论数量设为0
            jdbcTemplate.update(
                    "UPDATE recipes SET AggregatedRating = NULL, ReviewCount = 0 WHERE RecipeId = ?",
                    recipeId
            );
        }

        // 返回更新后的食谱记录
        return recipeService.getRecipeById(recipeId);
    }

}
