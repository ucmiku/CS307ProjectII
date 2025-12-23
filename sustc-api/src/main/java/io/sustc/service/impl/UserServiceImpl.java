package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.UserService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Override
    public long register(RegisterUserReq req) {
        if (req == null) {
            return -1;
        }

        if (req.getName() == null || req.getName().isEmpty()) {
            return -1;
        }

        if (req.getGender() == null || (req.getGender() != RegisterUserReq.Gender.MALE && req.getGender() != RegisterUserReq.Gender.FEMALE)) {
            return -1;
        }

        String birthday = req.getBirthday();
        int age;
        if (birthday != null && !birthday.isEmpty()) {
            try {
                LocalDate birthDate = LocalDate.parse(birthday);
                age = Period.between(birthDate, LocalDate.now()).getYears();
            } catch (Exception e) {
                return -1;
            }
        } else {
            return -1;
        }

        if (age <= 0) {
            return -1;
        }

        Boolean userExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE AuthorName = ?)",
                Boolean.class,
                req.getName()
        );
        if (userExists) {
            return -1;
        }

        Long newUserId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(AuthorId), 0) + 1 FROM users",
                Long.class
        );
        if (newUserId == null) {
            newUserId = 1L;
        }

        String genderStr = req.getGender() == RegisterUserReq.Gender.MALE ? "Male" : "Female";
        jdbcTemplate.update(
                "INSERT INTO users (AuthorId, AuthorName, Gender, Age, Followers, Following, Password, IsDeleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                newUserId,
                req.getName(),
                genderStr,
                age,
                0,  // followers
                0,  // following
                req.getPassword() != null ? req.getPassword() : "",  // password
                false  // isDeleted
        );

        return newUserId;
    }

    @Override
    public long login(AuthInfo auth) {
        if (auth == null) {
            return -1;
        }

        long authorId = auth.getAuthorId();
        String password = auth.getPassword();

        if (authorId <= 0 || password == null || password.isEmpty()) {
            return -1;
        }

        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    authorId
            );

            if (isDeleted) {
                return -1;
            }

            String storedPassword = jdbcTemplate.queryForObject(
                    "SELECT Password FROM users WHERE AuthorId = ?",
                    String.class,
                    authorId
            );

            if (storedPassword.equals(password)) {
                return authorId;
            } else {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public boolean deleteAccount(AuthInfo auth, long userId) {
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long operatorId = auth.getAuthorId();

        if (operatorId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        if (operatorId != userId) {
            throw new SecurityException("User can only delete their own account");
        }

        try {
            Boolean operatorIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    operatorId
            );

            if (operatorIsDeleted) {
                throw new SecurityException("Operator user is inactive or does not exist");
            }

            jdbcTemplate.update(
                    "UPDATE users SET IsDeleted = true WHERE AuthorId = ?",
                    userId
            );

            jdbcTemplate.update(
                    "DELETE FROM user_follows WHERE FollowerId = ? OR FollowingId = ?",
                    userId, userId
            );

            return true;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Target user does not exist");
        }
    }

    @Override
    public boolean follow(AuthInfo auth, long followeeId) {
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long followerId = auth.getAuthorId();

        if (followerId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        if (followerId == followeeId) {
            throw new SecurityException("User cannot follow themselves");
        }

        try {
            Boolean followerIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    followerId
            );

            if (followerIsDeleted) {
                throw new SecurityException("Follower user is inactive or does not exist");
            }

            Boolean followeeIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    followeeId
            );

            if (followeeIsDeleted) {
                throw new SecurityException("Followee user is inactive or does not exist");
            }

            Boolean isFollowing = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM user_follows WHERE FollowerId = ? AND FollowingId = ?)",
                    Boolean.class,
                    followerId, followeeId
            );

            if (isFollowing) {
                jdbcTemplate.update(
                        "DELETE FROM user_follows WHERE FollowerId = ? AND FollowingId = ?",
                        followerId, followeeId
                );
                return false;
            } else {
                // 未关注，执行关注
                jdbcTemplate.update(
                        "INSERT INTO user_follows (FollowerId, FollowingId) VALUES (?, ?)",
                        followerId, followeeId
                );
                return true;
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }
    }


    @Getter
    private final RowMapper<UserRecord> userRowMapper = (rs, rowNum) -> {
        UserRecord record = new UserRecord();
        record.setAuthorId(rs.getLong("AuthorId"));
        record.setAuthorName(rs.getString("AuthorName"));
        record.setGender(rs.getString("Gender"));
        record.setAge(rs.getInt("Age"));
        record.setFollowers(rs.getInt("Followers"));
        record.setFollowing(rs.getInt("Following"));
        record.setPassword(rs.getString("Password"));
        record.setDeleted(rs.getBoolean("IsDeleted"));
        return record;
    };

    @Override
    public UserRecord getById(long userId) {
        try {
            // 查询用户基本信息
            UserRecord record = jdbcTemplate.queryForObject(
                    "SELECT AuthorId, AuthorName, Gender, Age, Password, IsDeleted FROM users WHERE AuthorId = ?",
                    (rs, rowNum) -> {
                        UserRecord r = new UserRecord();
                        r.setAuthorId(rs.getLong("AuthorId"));
                        r.setAuthorName(rs.getString("AuthorName"));
                        r.setGender(rs.getString("Gender"));
                        r.setAge(rs.getInt("Age"));
                        r.setPassword(rs.getString("Password"));
                        r.setDeleted(rs.getBoolean("IsDeleted"));
                        return r;
                    },
                    userId
            );

            Integer followersCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_follows WHERE FollowingId = ?",
                    Integer.class,
                    userId
            );
            if (record != null) {
                record.setFollowers(followersCount);
            }

            Integer followingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_follows WHERE FollowerId = ?",
                    Integer.class,
                    userId
            );
            if (record != null) {
                record.setFollowing(followingCount);
            }

            List<Long> followerUserList = jdbcTemplate.queryForList(
                    "SELECT FollowerId FROM user_follows WHERE FollowingId = ? ORDER BY FollowerId",
                    Long.class,
                    userId
            );
            long[] followerUsers = followerUserList.stream().mapToLong(Long::longValue).toArray();
            if (record != null) {
                record.setFollowerUsers(followerUsers);
            }

            List<Long> followingUserList = jdbcTemplate.queryForList(
                    "SELECT FollowingId FROM user_follows WHERE FollowerId = ? ORDER BY FollowingId",
                    Long.class,
                    userId
            );
            long[] followingUsers = followingUserList.stream().mapToLong(Long::longValue).toArray();
            if (record != null) {
                record.setFollowingUsers(followingUsers);
            }

            return record;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    @Transactional
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();

        if (userId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );

            if (isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }

            StringBuilder sql = new StringBuilder("UPDATE users SET ");
            List<Object> params = new ArrayList<>();

            boolean first = true;
            if (gender != null) {
                if (!"Male".equals(gender) && !"Female".equals(gender)) {
                    throw new IllegalArgumentException("Invalid gender value");
                }
                sql.append("Gender = ?");
                params.add(gender);
                first = false;
            }

            if (age != null) {
                if (age <= 0) {
                    throw new IllegalArgumentException("Age must be a positive integer");
                }
                if (!first) sql.append(", ");
                sql.append("Age = ?");
                params.add(age);
            }
            if (params.isEmpty()) {
                return;
            }

            sql.append(" WHERE AuthorId = ?");
            params.add(userId);

            int updatedRows = jdbcTemplate.update(sql.toString(), params.toArray());
            if (updatedRows == 0) {
                throw new SecurityException("User does not exist");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }
    }

    private final RowMapper<FeedItem> feedItemRowMapper = (rs, rowNum) -> {
        FeedItem item = new FeedItem();
        item.setRecipeId(rs.getLong("RecipeId"));
        item.setName(rs.getString("Name"));
        item.setAuthorId(rs.getLong("AuthorId"));
        item.setAuthorName(rs.getString("AuthorName"));
        java.sql.Timestamp timestamp = rs.getTimestamp("DatePublished");
        if (timestamp != null) {
            item.setDatePublished(timestamp.toLocalDateTime().atZone(java.time.ZoneOffset.UTC).toInstant());
        }
        // Handle BigDecimal to Double conversion for PostgreSQL
        Object ratingObj = rs.getObject("AggregatedRating");
        item.setAggregatedRating(ratingObj != null ? ((Number) ratingObj).doubleValue() : null);
        item.setReviewCount(rs.getInt("ReviewCount"));
        return item;
    };

    @Override
    public PageResult<FeedItem> feed(AuthInfo auth, int page, int size, String category) {
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();
        if (userId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }
        if (page < 1) {
            page = 1;
        }

        if (size < 1) {
            size = 1;
        } else if (size > 200) {
            size = 200;
        }

        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );

            if (isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }

            StringBuilder whereClause = new StringBuilder(
                    " WHERE r.AuthorId IN (SELECT FollowingId FROM user_follows WHERE FollowerId = ?)");
            List<Object> params = new ArrayList<>();
            params.add(userId);

            if (category != null && !category.isEmpty()) {
                whereClause.append(" AND r.RecipeCategory = ?");
                params.add(category);
            }

            String countSql = "SELECT COUNT(*) FROM recipes r" + whereClause;
            Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());

            String sql = "SELECT r.RecipeId, r.Name, r.AuthorId, u.AuthorName, r.DatePublished, r.AggregatedRating, r.ReviewCount " + "FROM recipes r " +
                    "JOIN users u ON r.AuthorId = u.AuthorId " +
                    whereClause +
                    " ORDER BY r.DatePublished DESC, r.RecipeId DESC " +
                    "LIMIT ? OFFSET ?";

            params.add(size);
            params.add((page - 1) * size);

            List<FeedItem> items = jdbcTemplate.query(sql, feedItemRowMapper, params.toArray());

            return PageResult.<FeedItem>builder()
                    .items(items)
                    .page(page)
                    .size(size)
                    .total(total)
                    .build();
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }
    }

    @Override
    public Map<String, Object> getUserWithHighestFollowRatio() {
        try {
            String sql = """
                SELECT
                    u.AuthorId,
                    u.AuthorName,
                    COALESCE(follower_counts.follower_count, 0) AS follower_count,
                    COALESCE(following_counts.following_count, 0) AS following_count,
                    CASE
                        WHEN COALESCE(following_counts.following_count, 0) > 0
                        THEN COALESCE(follower_counts.follower_count, 0) * 1.0 / COALESCE(following_counts.following_count, 0)
                        ELSE NULL
                    END AS ratio
                FROM users u
                LEFT JOIN (
                    SELECT FollowingId AS AuthorId, COUNT(*) AS follower_count
                    FROM user_follows
                    GROUP BY FollowingId
                ) follower_counts ON u.AuthorId = follower_counts.AuthorId
                LEFT JOIN (
                    SELECT FollowerId AS AuthorId, COUNT(*) AS following_count
                    FROM user_follows
                    GROUP BY FollowerId
                ) following_counts ON u.AuthorId = following_counts.AuthorId
                WHERE u.IsDeleted = FALSE
                  AND COALESCE(following_counts.following_count, 0) > 0
                ORDER BY
                    CASE
                        WHEN COALESCE(following_counts.following_count, 0) > 0
                        THEN COALESCE(follower_counts.follower_count, 0) * 1.0 / COALESCE(following_counts.following_count, 0)
                        ELSE NULL
                    END DESC,
                    u.AuthorId ASC
                LIMIT 1
                """;

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            if (results.isEmpty()) {
                return null;
            }

            Map<String, Object> result = results.get(0);
            Map<String, Object> response = new HashMap<>();
            response.put("AuthorId", result.get("authorid"));
            response.put("AuthorName", result.get("authorname"));
            // Handle BigDecimal to Double conversion for PostgreSQL
            Object ratioObj = result.get("ratio");
            response.put("Ratio", ratioObj != null ? ((Number) ratioObj).doubleValue() : null);

            return response;
        } catch (Exception e) {
            return null;
        }
    }

}
