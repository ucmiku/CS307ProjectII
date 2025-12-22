package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.RecipeService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;


    @Override
    public String getNameFromID(long id) {
        if (id <= 0) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT Name FROM recipes WHERE RecipeId = ?",
                    String.class,
                    id
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public RecipeRecord getRecipeById(long recipeId) {
        if (recipeId <= 0) {
            throw new IllegalArgumentException("recipeId must be positive");
        }

        final String sql = """
                SELECT r.RecipeId,
                       r.Name,
                       r.AuthorId,
                       u.AuthorName,
                       r.CookTime,
                       r.PrepTime,
                       r.TotalTime,
                       r.DatePublished,
                       r.Description,
                       r.RecipeCategory,
                       COALESCE(ri.parts, ARRAY[]::text[]) AS RecipeIngredientParts,
                       r.AggregatedRating,
                       r.ReviewCount,
                       r.Calories,
                       r.FatContent,
                       r.SaturatedFatContent,
                       r.CholesterolContent,
                       r.SodiumContent,
                       r.CarbohydrateContent,
                       r.FiberContent,
                       r.SugarContent,
                       r.ProteinContent,
                       r.RecipeServings,
                       r.RecipeYield
                FROM recipes r
                JOIN users u ON u.AuthorId = r.AuthorId
                LEFT JOIN LATERAL (
                    SELECT array_agg(IngredientPart ORDER BY lower(IngredientPart), IngredientPart) AS parts
                    FROM recipe_ingredients
                    WHERE RecipeId = r.RecipeId
                ) ri ON true
                WHERE r.RecipeId = ?
                """;

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapRecipeRecord(rs), recipeId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }


    @Override
    public PageResult<RecipeRecord> searchRecipes(String keyword, String category, Double minRating,
                                                  Integer page, Integer size, String sort) {
        if (page == null || page < 1 || size == null || size <= 0) {
            throw new IllegalArgumentException("page must be >= 1 and size must be > 0");
        }

        StringBuilder where = new StringBuilder(" WHERE u.IsDeleted = FALSE ");
        List<Object> params = new ArrayList<>();

        if (StringUtils.hasText(keyword)) {
            where.append(" AND (r.Name ILIKE ? OR r.Description ILIKE ?) ");
            String pattern = "%" + keyword.trim() + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (StringUtils.hasText(category)) {
            where.append(" AND r.RecipeCategory = ? ");
            params.add(category.trim());
        }
        if (minRating != null) {
            where.append(" AND r.AggregatedRating >= ? ");
            params.add(minRating);
        }

        String orderBy = switch (sort == null ? "" : sort) {
            case "rating_desc" -> " ORDER BY r.AggregatedRating DESC NULLS LAST, r.DatePublished DESC NULLS LAST, r.RecipeId ASC ";
            case "date_desc" -> " ORDER BY r.DatePublished DESC NULLS LAST, r.RecipeId ASC ";
            case "calories_asc" -> " ORDER BY r.Calories ASC NULLS LAST, r.RecipeId ASC ";
            default -> " ORDER BY r.RecipeId ASC ";
        };

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipes r JOIN users u ON u.AuthorId = r.AuthorId " + where,
                Long.class,
                params.toArray()
        );

        int offset = (page - 1) * size;
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(offset);

        final String sql = """
                SELECT r.RecipeId,
                       r.Name,
                       r.AuthorId,
                       u.AuthorName,
                       r.CookTime,
                       r.PrepTime,
                       r.TotalTime,
                       r.DatePublished,
                       r.Description,
                       r.RecipeCategory,
                       COALESCE(ri.parts, ARRAY[]::text[]) AS RecipeIngredientParts,
                       r.AggregatedRating,
                       r.ReviewCount,
                       r.Calories,
                       r.FatContent,
                       r.SaturatedFatContent,
                       r.CholesterolContent,
                       r.SodiumContent,
                       r.CarbohydrateContent,
                       r.FiberContent,
                       r.SugarContent,
                       r.ProteinContent,
                       r.RecipeServings,
                       r.RecipeYield
                FROM recipes r
                JOIN users u ON u.AuthorId = r.AuthorId
                LEFT JOIN LATERAL (
                    SELECT array_agg(IngredientPart ORDER BY lower(IngredientPart), IngredientPart) AS parts
                    FROM recipe_ingredients
                    WHERE RecipeId = r.RecipeId
                ) ri ON true
                """;

        List<RecipeRecord> items = jdbcTemplate.query(
                sql + where + orderBy + " LIMIT ? OFFSET ? ",
                (rs, rowNum) -> mapRecipeRecord(rs),
                pageParams.toArray()
        );

        return PageResult.<RecipeRecord>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    @Override
    @Transactional
    public long createRecipe(RecipeRecord dto, AuthInfo auth) {
        assertActiveUser(auth);
        if (dto == null) {
            throw new IllegalArgumentException("recipe dto is null");
        }
        if (!StringUtils.hasText(dto.getName())) {
            throw new IllegalArgumentException("recipe name is empty");
        }

        long recipeId = dto.getRecipeId();
        if (recipeId <= 0) {
            throw new IllegalArgumentException("recipeId must be positive");
        }

        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM recipes WHERE RecipeId = ?)",
                Boolean.class,
                recipeId
        );
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalArgumentException("recipeId already exists");
        }

        String cook = StringUtils.hasText(dto.getCookTime()) ? dto.getCookTime().trim() : null;
        String prep = StringUtils.hasText(dto.getPrepTime()) ? dto.getPrepTime().trim() : null;
        String totalTime = StringUtils.hasText(dto.getTotalTime()) ? dto.getTotalTime().trim() : null;

        Duration cookDur = Duration.ZERO;
        Duration prepDur = Duration.ZERO;
        if (cook != null) {
            cookDur = parseDurationStrict(cook);
        }
        if (prep != null) {
            prepDur = parseDurationStrict(prep);
        }
        if (totalTime == null && (cook != null || prep != null)) {
            try {
                Duration total = cookDur.plus(prepDur);
                if (total.isNegative()) {
                    throw new IllegalArgumentException("negative duration");
                }
                total.toSeconds();
                totalTime = total.toString();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("duration overflow");
            }
        } else if (totalTime != null) {
            parseDurationStrict(totalTime);
        }

        jdbcTemplate.update(
                "INSERT INTO recipes (" +
                        "RecipeId, Name, AuthorId, CookTime, PrepTime, TotalTime, DatePublished, Description, RecipeCategory, " +
                        "AggregatedRating, ReviewCount, Calories, FatContent, SaturatedFatContent, CholesterolContent, SodiumContent, " +
                        "CarbohydrateContent, FiberContent, SugarContent, ProteinContent, RecipeServings, RecipeYield" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                recipeId,
                dto.getName().trim(),
                auth.getAuthorId(),
                cook,
                prep,
                totalTime,
                dto.getDatePublished(),
                dto.getDescription(),
                dto.getRecipeCategory(),
                0.0,
                0,
                dto.getCalories(),
                dto.getFatContent(),
                dto.getSaturatedFatContent(),
                dto.getCholesterolContent(),
                dto.getSodiumContent(),
                dto.getCarbohydrateContent(),
                dto.getFiberContent(),
                dto.getSugarContent(),
                dto.getProteinContent(),
                dto.getRecipeServings(),
                dto.getRecipeYield()
        );

        String[] parts = dto.getRecipeIngredientParts() == null ? new String[0] : dto.getRecipeIngredientParts();
        if (parts.length > 0) {
            String[] sorted = Arrays.stream(parts)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .sorted(String::compareToIgnoreCase)
                    .toArray(String[]::new);
            if (sorted.length > 0) {
                jdbcTemplate.batchUpdate(
                        "INSERT INTO recipe_ingredients (RecipeId, IngredientPart) VALUES (?, ?) " +
                                "ON CONFLICT (RecipeId, IngredientPart) DO NOTHING",
                        Arrays.asList(sorted),
                        sorted.length,
                        (ps, part) -> {
                            ps.setLong(1, recipeId);
                            ps.setString(2, part);
                        }
                );
            }
        }

        return recipeId;
    }

    @Override
    @Transactional
    public void deleteRecipe(long recipeId, AuthInfo auth) {
        assertActiveUser(auth);

        Long authorId = jdbcTemplate.query(
                "SELECT AuthorId FROM recipes WHERE RecipeId = ?",
                (rs, rowNum) -> rs.getLong(1),
                recipeId
        ).stream().findFirst().orElse(null);
        if (authorId == null) {
            return;
        }
        if (!Objects.equals(authorId, auth.getAuthorId())) {
            throw new SecurityException("only recipe author can delete");
        }

        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId IN (SELECT ReviewId FROM reviews WHERE RecipeId = ?)",
                recipeId
        );
        jdbcTemplate.update("DELETE FROM reviews WHERE RecipeId = ?", recipeId);
        jdbcTemplate.update("DELETE FROM recipe_ingredients WHERE RecipeId = ?", recipeId);
        jdbcTemplate.update("DELETE FROM recipes WHERE RecipeId = ?", recipeId);

    }

    @Override
    @Transactional
    public void updateTimes(AuthInfo auth, long recipeId, String cookTimeIso, String prepTimeIso) {
        assertActiveUser(auth);
        if (recipeId <= 0) {
            throw new IllegalArgumentException("recipeId must be positive");
        }

        long recipeAuthor = jdbcTemplate.query(
                "SELECT AuthorId FROM recipes WHERE RecipeId = ?",
                (rs, rowNum) -> rs.getLong(1),
                recipeId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("recipe not found"));
        if (recipeAuthor != auth.getAuthorId()) {
            throw new SecurityException("only recipe author can update times");
        }

        Map<String, Object> current = jdbcTemplate.queryForMap(
                "SELECT CookTime, PrepTime FROM recipes WHERE RecipeId = ?",
                recipeId
        );
        String currentCook = (String) current.get("cooktime");
        String currentPrep = (String) current.get("preptime");

        Duration cookDur;
        Duration prepDur;

        if (cookTimeIso != null) {
            cookDur = parseDurationStrict(cookTimeIso);
        } else {
            cookDur = parseDurationLenient(currentCook);
        }
        if (prepTimeIso != null) {
            prepDur = parseDurationStrict(prepTimeIso);
        } else {
            prepDur = parseDurationLenient(currentPrep);
        }

        Duration total;
        try {
            total = cookDur.plus(prepDur);
            if (total.isNegative()) {
                throw new IllegalArgumentException("negative duration");
            }
            total.toSeconds();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("duration overflow");
        }

        String nextCook = cookTimeIso != null ? cookTimeIso.trim() : currentCook;
        String nextPrep = prepTimeIso != null ? prepTimeIso.trim() : currentPrep;
        String totalIso = total.toString();

        jdbcTemplate.update(
                "UPDATE recipes SET CookTime = ?, PrepTime = ?, TotalTime = ? WHERE RecipeId = ?",
                nextCook,
                nextPrep,
                totalIso,
                recipeId
        );
    }

    @Override
    public Map<String, Object> getClosestCaloriePair() {
        final String sql = """
                WITH ordered AS (
                    SELECT RecipeId,
                           Calories::double precision AS cal,
                           LEAD(RecipeId) OVER (ORDER BY Calories, RecipeId) AS next_id,
                           LEAD(Calories::double precision) OVER (ORDER BY Calories, RecipeId) AS next_cal
                    FROM recipes
                    WHERE Calories IS NOT NULL
                )
                SELECT LEAST(RecipeId, next_id) AS "RecipeA",
                       GREATEST(RecipeId, next_id) AS "RecipeB",
                       CASE WHEN RecipeId <= next_id THEN cal ELSE next_cal END AS "CaloriesA",
                       CASE WHEN RecipeId <= next_id THEN next_cal ELSE cal END AS "CaloriesB",
                       ABS(next_cal - cal) AS "Difference"
                FROM ordered
                WHERE next_id IS NOT NULL
                ORDER BY "Difference" ASC, "RecipeA" ASC, "RecipeB" ASC
                LIMIT 1
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    @Override
    public List<Map<String, Object>> getTop3MostComplexRecipesByIngredients() {
        final String sql = """
                SELECT r.RecipeId AS "RecipeId",
                       r.Name AS "Name",
                       COUNT(*)::int AS "IngredientCount"
                FROM recipe_ingredients ri
                JOIN recipes r ON r.RecipeId = ri.RecipeId
                GROUP BY r.RecipeId, r.Name
                ORDER BY COUNT(*) DESC, r.RecipeId ASC
                LIMIT 3
                """;
        return jdbcTemplate.queryForList(sql);
    }

    private void assertActiveUser(AuthInfo auth) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new SecurityException("invalid auth");
        }
        try {
            Boolean deleted = jdbcTemplate.queryForObject(
                    "SELECT IsDeleted FROM users WHERE AuthorId = ?",
                    Boolean.class,
                    auth.getAuthorId()
            );
            if (Boolean.TRUE.equals(deleted)) {
                throw new SecurityException("user is deleted");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("user not found");
        }
    }

    private static Duration parseDurationStrict(String iso) {
        if (!StringUtils.hasText(iso)) {
            throw new IllegalArgumentException("duration is blank");
        }
        try {
            Duration d = Duration.parse(iso.trim());
            if (d.isNegative()) {
                throw new IllegalArgumentException("negative duration");
            }
            d.toSeconds();
            return d;
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("invalid duration");
        }
    }

    private static Duration parseDurationLenient(String iso) {
        if (!StringUtils.hasText(iso)) {
            return Duration.ZERO;
        }
        try {
            Duration d = Duration.parse(iso.trim());
            if (d.isNegative()) {
                throw new IllegalArgumentException("negative duration");
            }
            d.toSeconds();
            return d;
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("invalid duration");
        }
    }

    private static RecipeRecord mapRecipeRecord(ResultSet rs) throws SQLException {
        Array arr = rs.getArray("RecipeIngredientParts");
        String[] parts;
        if (arr == null) {
            parts = new String[0];
        } else {
            Object raw = arr.getArray();
            parts = raw instanceof String[] ? (String[]) raw : Arrays.copyOf((Object[]) raw, ((Object[]) raw).length, String[].class);
        }

        return RecipeRecord.builder()
                .RecipeId(rs.getLong("RecipeId"))
                .name(rs.getString("Name"))
                .authorId(rs.getLong("AuthorId"))
                .authorName(rs.getString("AuthorName"))
                .cookTime(rs.getString("CookTime"))
                .prepTime(rs.getString("PrepTime"))
                .totalTime(rs.getString("TotalTime"))
                .datePublished(rs.getTimestamp("DatePublished"))
                .description(rs.getString("Description"))
                .recipeCategory(rs.getString("RecipeCategory"))
                .recipeIngredientParts(parts)
                .aggregatedRating(rs.getFloat("AggregatedRating"))
                .reviewCount(rs.getInt("ReviewCount"))
                .calories(rs.getFloat("Calories"))
                .fatContent(rs.getFloat("FatContent"))
                .saturatedFatContent(rs.getFloat("SaturatedFatContent"))
                .cholesterolContent(rs.getFloat("CholesterolContent"))
                .sodiumContent(rs.getFloat("SodiumContent"))
                .carbohydrateContent(rs.getFloat("CarbohydrateContent"))
                .fiberContent(rs.getFloat("FiberContent"))
                .sugarContent(rs.getFloat("SugarContent"))
                .proteinContent(rs.getFloat("ProteinContent"))
                .recipeServings(rs.getInt("RecipeServings"))
                .recipeYield(rs.getString("RecipeYield"))
                .build();
    }

}
