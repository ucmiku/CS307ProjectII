package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.UserService;
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
        // 检查请求参数
        if (req == null) {
            return -1;
        }

        // 检查必需字段
        if (req.getName() == null || req.getName().isEmpty()) {
            return -1;
        }

        if (req.getGender() == null || (req.getGender() != RegisterUserReq.Gender.MALE && req.getGender() != RegisterUserReq.Gender.FEMALE)) {
            return -1;
        }

        // 检查年龄是否为有效的正整数
        String birthday = req.getBirthday();
        int age = 0;
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

        // 检查是否已存在同名用户
        Boolean userExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE AuthorName = ?)",
                Boolean.class,
                req.getName()
        );

        if (userExists != null && userExists) {
            return -1;
        }

        // 使用数据库序列生成新的用户ID
        Long newUserId;
        try {
            newUserId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(AuthorId), 0) + 1 FROM users",
                    Long.class
            );
        } catch (Exception e) {
            newUserId = 1L;
        }

        // 插入新用户
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
        // 检查认证信息
        if (auth == null) {
            return -1;
        }

        long authorId = auth.getAuthorId();
        String password = auth.getPassword();

        // 检查authorId和password是否有效
        if (authorId <= 0 || password == null || password.isEmpty()) {
            return -1;
        }

        try {
            // 检查用户是否存在且未被删除
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    authorId
            );

            if (isDeleted == null || isDeleted) {
                return -1;
            }

            // 检查密码是否匹配
            String storedPassword = jdbcTemplate.queryForObject(
                    "SELECT Password FROM users WHERE AuthorId = ?",
                    String.class,
                    authorId
            );

            if (storedPassword != null && storedPassword.equals(password)) {
                return authorId;
            } else {
                return -1;
            }
        } catch (EmptyResultDataAccessException e) {
            // 用户不存在
            return -1;
        } catch (Exception e) {
            // 任何其他异常都返回 -1
            return -1;
        }
    }

    @Override
    public boolean deleteAccount(AuthInfo auth, long userId) {
        // 检查认证信息
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long operatorId = auth.getAuthorId();

        // 检查operatorId是否有效
        if (operatorId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 检查操作用户是否有权限删除目标用户（只能删除自己）
        if (operatorId != userId) {
            throw new SecurityException("User can only delete their own account");
        }

        try {
            // 检查操作用户是否存在且未被删除
            Boolean operatorIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    operatorId
            );

            if (operatorIsDeleted == null || operatorIsDeleted) {
                throw new SecurityException("Operator user is inactive or does not exist");
            }

            // 执行软删除
            jdbcTemplate.update(
                    "UPDATE users SET IsDeleted = true WHERE AuthorId = ?",
                    userId
            );

            // 删除所有关注关系
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
        // 检查认证信息
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long followerId = auth.getAuthorId();

        // 检查followerId是否有效
        if (followerId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 检查用户不能关注自己
        if (followerId == followeeId) {
            throw new SecurityException("User cannot follow themselves");
        }

        try {
            // 检查关注者是否存在且未被删除
            Boolean followerIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    followerId
            );

            if (followerIsDeleted == null || followerIsDeleted) {
                throw new SecurityException("Follower user is inactive or does not exist");
            }

            // 检查被关注者是否存在且未被删除
            Boolean followeeIsDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    followeeId
            );

            if (followeeIsDeleted == null || followeeIsDeleted) {
                throw new SecurityException("Followee user is inactive or does not exist");
            }

            // 检查是否已经关注
            Boolean isFollowing = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM user_follows WHERE FollowerId = ? AND FollowingId = ?)",
                    Boolean.class,
                    followerId, followeeId
            );

            if (isFollowing != null && isFollowing) {
                // 已经关注，执行取消关注
                jdbcTemplate.update(
                        "DELETE FROM user_follows WHERE FollowerId = ? AND FollowingId = ?",
                        followerId, followeeId
                );
                return false; // 取消关注后状态为未关注
            } else {
                // 未关注，执行关注
                jdbcTemplate.update(
                        "INSERT INTO user_follows (FollowerId, FollowingId) VALUES (?, ?)",
                        followerId, followeeId
                );
                return true; // 关注后状态为已关注
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist");
        }
    }


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

            // 动态计算 followers 数量（有多少人关注了这个用户）
            Integer followersCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_follows WHERE FollowingId = ?",
                    Integer.class,
                    userId
            );
            record.setFollowers(followersCount != null ? followersCount : 0);

            // 动态计算 following 数量（这个用户关注了多少人）
            Integer followingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_follows WHERE FollowerId = ?",
                    Integer.class,
                    userId
            );
            record.setFollowing(followingCount != null ? followingCount : 0);

            // 查询 followerUsers 列表（关注这个用户的用户ID列表）
            List<Long> followerUserList = jdbcTemplate.queryForList(
                    "SELECT FollowerId FROM user_follows WHERE FollowingId = ? ORDER BY FollowerId",
                    Long.class,
                    userId
            );
            record.setFollowerUsers(followerUserList != null ? followerUserList.stream().mapToLong(Long::longValue).toArray() : new long[0]);

            // 查询 followingUsers 列表（这个用户关注的用户ID列表）
            List<Long> followingUserList = jdbcTemplate.queryForList(
                    "SELECT FollowingId FROM user_follows WHERE FollowerId = ? ORDER BY FollowingId",
                    Long.class,
                    userId
            );
            record.setFollowingUsers(followingUserList != null ? followingUserList.stream().mapToLong(Long::longValue).toArray() : new long[0]);

            return record;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    @Transactional
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        // 检查认证信息
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();

        // 检查userId是否有效
        if (userId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        try {
            // 检查用户是否存在且未被删除
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );

            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }

            // 构建更新语句
            StringBuilder sql = new StringBuilder("UPDATE users SET ");
            List<Object> params = new ArrayList<>();

            boolean first = true;

            // 更新性别
            if (gender != null) {
                if (!"Male".equals(gender) && !"Female".equals(gender)) {
                    throw new IllegalArgumentException("Invalid gender value");
                }
                if (!first) sql.append(", ");
                sql.append("Gender = ?");
                params.add(gender);
                first = false;
            }

            // 更新年龄
            if (age != null) {
                if (age <= 0) {
                    throw new IllegalArgumentException("Age must be a positive integer");
                }
                if (!first) sql.append(", ");
                sql.append("Age = ?");
                params.add(age);
                first = false;
            }

            // 如果没有要更新的字段，直接返回
            if (params.isEmpty()) {
                return;
            }

            sql.append(" WHERE AuthorId = ?");
            params.add(userId);

            // 执行更新
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
        // 处理时间字段，确保使用 UTC 时区
        java.sql.Timestamp timestamp = rs.getTimestamp("DatePublished");
        if (timestamp != null) {
            // 将 Timestamp 转换为 UTC 时区的 Instant
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
        // 检查认证信息
        if (auth == null) {
            throw new SecurityException("Invalid authentication info");
        }

        long userId = auth.getAuthorId();

        // 检查userId是否有效
        if (userId <= 0) {
            throw new SecurityException("Invalid authentication info");
        }

        // 检查分页参数
        if (page < 1) {
            page = 1;
        }

        if (size < 1) {
            size = 1;
        } else if (size > 200) {
            size = 200;
        }

        try {
            // 检查用户是否存在且未被删除
            Boolean isDeleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    userId
            );

            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is inactive or does not exist");
            }

            // 构建查询条件
            StringBuilder whereClause = new StringBuilder(
                    " WHERE r.AuthorId IN (SELECT FollowingId FROM user_follows WHERE FollowerId = ?)");
            List<Object> params = new ArrayList<>();
            params.add(userId);

            // 添加分类筛选条件
            if (category != null && !category.isEmpty()) {
                whereClause.append(" AND r.RecipeCategory = ?");
                params.add(category);
            }

            // 查询总数
            String countSql = "SELECT COUNT(*) FROM recipes r" + whereClause;
            Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
            if (total == null) total = 0L;

            // 查询数据
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
