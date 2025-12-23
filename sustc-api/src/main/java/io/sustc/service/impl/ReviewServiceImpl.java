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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
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
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new IllegalArgumentException("Invalid authentication info");
        }

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        Long newReviewId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(ReviewId), 0) + 1 FROM reviews",
                Long.class
        );
        if (newReviewId == null) {
            newReviewId = 1L;
        }
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());

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

        refreshRecipeAggregatedRating(recipeId);

        return newReviewId;
    }

    @Override
    @Transactional
    public void editReview(AuthInfo auth, long recipeId, long reviewId, int rating, String review) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        long actualRecipeId;
        long reviewAuthorId;
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

        if (actualRecipeId != recipeId) {
            throw new IllegalArgumentException("Review does not belong to the specified recipe");
        }

        if (reviewAuthorId != userId) {
            throw new SecurityException("Only the review author can edit the review");
        }

        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        jdbcTemplate.update(
                "UPDATE reviews SET Rating = ?, Review = ?, DateModified = ? WHERE ReviewId = ?",
                rating,
                review,
                now,
                reviewId
        );

        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public void deleteReview(AuthInfo auth, long recipeId, long reviewId) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );
            if (isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }

        long actualRecipeId;
        long reviewAuthorId;
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

        if (actualRecipeId != recipeId) {
            throw new IllegalArgumentException("Review does not belong to the specified recipe");
        }

        if (reviewAuthorId != userId) {
            throw new SecurityException("Only the review author can delete the review");
        }

        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId = ?",
                reviewId
        );

        jdbcTemplate.update(
                "DELETE FROM reviews WHERE ReviewId = ?",
                reviewId
        );

        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public long likeReview(AuthInfo auth, long reviewId) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();
        long loginResult = userService.login(auth);
        if (loginResult == -1L) {
            throw new SecurityException("Authentication failed");
        }

        long reviewAuthorId;
        try {
            reviewAuthorId = jdbcTemplate.queryForObject(
                    "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        if (reviewAuthorId == userId) {
            throw new SecurityException("Users cannot like their own reviews");
        }

        Boolean alreadyLiked = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_likes WHERE ReviewId = ? AND AuthorId = ?)",
                Boolean.class,
                reviewId,
                userId
        );

        if (alreadyLiked) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        }

        jdbcTemplate.update(
                "INSERT INTO review_likes (ReviewId, AuthorId) VALUES (?, ?)",
                reviewId,
                userId
        );

        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                Long.class,
                reviewId
        );
    }

    @Override
    @Transactional
    public long unlikeReview(AuthInfo auth, long reviewId) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();
        long loginResult = userService.login(auth);
        if (loginResult == -1L) {
            throw new SecurityException("Authentication failed");
        }

        Boolean reviewExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reviews WHERE ReviewId = ?)",
                Boolean.class,
                reviewId
        );

        if (!reviewExists) {
            throw new IllegalArgumentException("Review does not exist");
        }

        Boolean hasLiked = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_likes WHERE ReviewId = ? AND AuthorId = ?)",
                Boolean.class,
                reviewId,
                userId
        );

        if (!hasLiked) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                    Long.class,
                    reviewId
            );
        }

        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId = ? AND AuthorId = ?",
                reviewId,
                userId
        );

        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?",
                Long.class,
                reviewId
        );
    }


    @Override
    public PageResult<ReviewRecord> listByRecipe(long recipeId, int page, int size, String sort) {
        if (page < 1) {
            throw new IllegalArgumentException("Page must be greater than 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }

        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        String orderBy = switch (sort == null ? "" : sort) {
            case "date_desc" -> " ORDER BY r.DateModified DESC, r.ReviewId DESC ";
            case "likes_desc" -> " ORDER BY LikeCount DESC, r.ReviewId DESC ";
            default -> " ORDER BY r.DateModified DESC, r.ReviewId DESC "; // 默认按时间倒序
        };

        String countSql = "SELECT COUNT(*) FROM reviews r WHERE r.RecipeId = ? AND r.Review IS NOT NULL";
        long total = jdbcTemplate.queryForObject(countSql, Long.class, recipeId);

        String sql = "SELECT r.*, COALESCE(rl.LikeCount, 0) AS LikeCount, u.AuthorName " +
                "FROM reviews r " +
                "LEFT JOIN (SELECT ReviewId, COUNT(*) AS LikeCount FROM review_likes GROUP BY ReviewId) rl ON r.ReviewId = rl.ReviewId " +
                "LEFT JOIN users u ON r.AuthorId = u.AuthorId " +
                "WHERE r.RecipeId = ? AND r.Review IS NOT NULL " +
                orderBy +
                "LIMIT ? OFFSET ?";

        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, recipeId, size, offset);
        
        if (rows.isEmpty()) {
            return PageResult.<ReviewRecord>builder()
                    .items(new ArrayList<>())
                    .page(page)
                    .size(size)
                    .total(total)
                    .build();
        }
        
        List<Long> reviewIds = rows.stream()
                .map(row -> ((Number) row.get("reviewid")).longValue())
                .toList();
        
        Map<Long, List<Long>> likesMap = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            String placeholders = reviewIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Map<String, Object>> likesRows = jdbcTemplate.queryForList(
                    "SELECT ReviewId, AuthorId FROM review_likes WHERE ReviewId IN (" + placeholders + ") ORDER BY ReviewId, AuthorId",
                    reviewIds.toArray()
            );
            for (Map<String, Object> likeRow : likesRows) {
                Long rid = ((Number) likeRow.get("reviewid")).longValue();
                Long uid = ((Number) likeRow.get("authorid")).longValue();
                likesMap.computeIfAbsent(rid, k -> new ArrayList<>()).add(uid);
            }
        }
        
        List<ReviewRecord> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ReviewRecord record = new ReviewRecord();
            long rid = ((Number) row.get("reviewid")).longValue();
            record.setReviewId(rid);
            record.setRecipeId(((Number) row.get("recipeid")).longValue());
            record.setAuthorId(((Number) row.get("authorid")).longValue());
            record.setAuthorName((String) row.get("authorname"));
            
            Object ratingObj = row.get("rating");
            record.setRating(ratingObj != null ? ((Number) ratingObj).floatValue() : 0.0f);
            record.setReview((String) row.get("review"));
            record.setDateSubmitted((java.sql.Timestamp) row.get("datesubmitted"));
            record.setDateModified((java.sql.Timestamp) row.get("datemodified"));
            
            List<Long> likeUserIds = likesMap.getOrDefault(rid, new ArrayList<>());
            record.setLikes(likeUserIds.stream().mapToLong(Long::longValue).toArray());
            
            items.add(record);
        }

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
        RecipeRecord recipe = recipeService.getRecipeById(recipeId);
        if (recipe == null) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        String sql = "SELECT AVG(Rating) as avgRating, COUNT(*) as reviewCount FROM reviews WHERE RecipeId = ? AND Review IS NOT NULL";
        Map<String, Object> result = jdbcTemplate.queryForMap(sql, recipeId);

        // Handle BigDecimal to Double conversion for PostgreSQL
        Object avgRatingObj = result.get("avgrating");
        Double avgRating = avgRatingObj != null ? ((Number) avgRatingObj).doubleValue() : null;
        Long reviewCountLong = (Long) result.get("reviewcount");
        int reviewCount = reviewCountLong != null ? reviewCountLong.intValue() : 0;

        if (reviewCount > 0 && avgRating != null && !avgRating.isNaN() && !avgRating.isInfinite()) {
            java.math.BigDecimal bd = new java.math.BigDecimal(avgRating);
            bd = bd.setScale(2, java.math.RoundingMode.HALF_UP);
            double roundedRating = bd.doubleValue();
            jdbcTemplate.update(
                    "UPDATE recipes SET AggregatedRating = ?, ReviewCount = ? WHERE RecipeId = ?",
                    roundedRating,
                    reviewCount,
                    recipeId
            );
        } else {
            jdbcTemplate.update(
                    "UPDATE recipes SET AggregatedRating = NULL, ReviewCount = 0 WHERE RecipeId = ?",
                    recipeId
            );
        }
        return recipeService.getRecipeById(recipeId);
    }

}
