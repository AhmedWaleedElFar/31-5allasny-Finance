package com.team31.financetracker.budget.service;

import com.team31.financetracker.budget.dto.BudgetAlertDTO;
import com.team31.financetracker.budget.dto.BudgetPerformanceDTO;
import com.team31.financetracker.budget.dto.BudgetUsageDTO;
import com.team31.financetracker.budget.dto.OverspentBudgetDTO;
import com.team31.financetracker.budget.dto.OverspentBudgetProjection;
import com.team31.financetracker.budget.dto.PerformanceProjection;
import com.team31.financetracker.budget.model.Budget;
import com.team31.financetracker.budget.model.BudgetEvent;
import com.team31.financetracker.budget.model.BudgetStatus;
import com.team31.financetracker.budget.model.BudgetUsageEvent;
import com.team31.financetracker.budget.model.BudgetUsageEventKey;
import com.team31.financetracker.budget.model.Category;
import com.team31.financetracker.budget.observer.MongoEventLogger;
import com.team31.financetracker.budget.repository.BudgetRepository;
import com.team31.financetracker.budget.repository.BudgetUsageEventRepository;
import com.team31.financetracker.budget.adapter.CassandraRowAdapter;
import com.team31.financetracker.contracts.feign.UserServiceClient;
import com.team31.financetracker.contracts.dto.UserDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final BudgetUsageEventRepository budgetUsageEventRepository;
    private final MongoEventLogger mongoEventLogger;
    private final CassandraRowAdapter cassandraRowAdapter = new CassandraRowAdapter();
    private final UserServiceClient userServiceClient;

    public BudgetService(BudgetRepository budgetRepository,
                         BudgetUsageEventRepository budgetUsageEventRepository,
                         MongoEventLogger mongoEventLogger,
                         UserServiceClient userServiceClient) {
        this.budgetRepository = budgetRepository;
        this.budgetUsageEventRepository = budgetUsageEventRepository;
        this.mongoEventLogger = mongoEventLogger;
        this.userServiceClient = userServiceClient;
    }

    // ──────────────────── Observer helper ────────────────────
    private void notifyObservers(String eventType, Map<String, Object> details) {
        try {
            mongoEventLogger.onEvent(eventType, details);
        } catch (Exception e) {
            log.warn("Observer notification failed for event [{}]: {}", eventType, e.getMessage());
        }
    }

    // ──────────────────── CRUD ────────────────────

    @CacheEvict(value = "budget-service", allEntries = true)
    public Budget createBudget(Budget budget) {
        // Ensure healthWeight default in metadata
        if (budget.getMetadata() == null) {
            budget.setMetadata(new HashMap<>());
        }
        budget.getMetadata().putIfAbsent("healthWeight", 1.0);

        Budget saved = budgetRepository.save(budget);

        notifyObservers(BudgetEvent.BUDGET_CREATED, Map.of(
                "budgetId", saved.getId(),
                "userId", saved.getUserId() != null ? saved.getUserId() : 0L,
                "category", saved.getCategory() != null ? saved.getCategory().name() : ""
        ));

        return saved;
    }

    public List<Budget> getAllBudgets() {
        return budgetRepository.findAll();
    }

    @Cacheable(value = "budget-service", key = "'budget-service::CRUD::' + #id", unless = "#result == null")
    public Budget getBudgetById(Long id) {
        log.info(">>> getBudgetById({}) called - NOT from cache", id);
        return budgetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
    }

    @CacheEvict(value = "budget-service", allEntries = true)
    public Budget updateBudget(Long id, Budget updatedBudget) {
        Budget existingBudget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        if (updatedBudget.getUserId() != null) existingBudget.setUserId(updatedBudget.getUserId());
        if (updatedBudget.getCategory() != null) existingBudget.setCategory(updatedBudget.getCategory());
        if (updatedBudget.getAmount() != null) existingBudget.setAmount(updatedBudget.getAmount());
        if (updatedBudget.getSpentAmount() != null) existingBudget.setSpentAmount(updatedBudget.getSpentAmount());
        if (updatedBudget.getPeriod() != null) existingBudget.setPeriod(updatedBudget.getPeriod());
        if (updatedBudget.getStartDate() != null) existingBudget.setStartDate(updatedBudget.getStartDate());
        if (updatedBudget.getEndDate() != null) existingBudget.setEndDate(updatedBudget.getEndDate());
        if (updatedBudget.getStatus() != null) existingBudget.setStatus(updatedBudget.getStatus());
        if (updatedBudget.getMetadata() != null) existingBudget.setMetadata(updatedBudget.getMetadata());

        Budget saved = budgetRepository.save(existingBudget);

        notifyObservers(BudgetEvent.METADATA_UPDATED, Map.of(
                "budgetId", saved.getId(),
                "action", "UPDATE"
        ));

        return saved;
    }

    @CacheEvict(value = "budget-service", allEntries = true)
    public void deleteBudget(Long id) {
        Budget existingBudget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
        budgetRepository.delete(existingBudget);

        notifyObservers(BudgetEvent.BUDGET_DELETED, Map.of("budgetId", id));
    }

    // ──────────────────── S4-F7: Purge ────────────────────

    @Transactional
    @CacheEvict(value = "budget-service", allEntries = true)
    public int purgeOldBudgets(int olderThanDays) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(olderThanDays);
        int count = budgetRepository.purgeOldBudgets(cutoffDate);

        notifyObservers(BudgetEvent.PURGED, Map.of(
                "olderThanDays", olderThanDays,
                "deletedCount", count
        ));

        return count;
    }

    // ──────────────────── S4-F3: Performance Summary ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F3::' + #userId + '::' + #startDate + '::' + #endDate",
            unless = "#result == null")
    public BudgetPerformanceDTO getBudgetPerformance(Long userId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59, 999999999);

        PerformanceProjection projection = budgetRepository.getBudgetPerformanceAggregates(userId, start, end);

        return new BudgetPerformanceDTO.Builder()
                .userId(userId)
                .totalBudgets(projection.getTotalBudgets() != null ? projection.getTotalBudgets() : 0)
                .totalBudgeted(projection.getTotalBudgeted() != null ? projection.getTotalBudgeted() : 0.0)
                .totalSpent(projection.getTotalSpent() != null ? projection.getTotalSpent() : 0.0)
                .averageUtilization(projection.getAverageUtilization() != null ? projection.getAverageUtilization() : 0.0)
                .exceededCount(projection.getExceededCount() != null ? projection.getExceededCount() : 0)
                .build();
    }

    // ──────────────────── S4-F5: Overspent ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F5::' + #minOverspend + '::' + #warningNotSent",
            unless = "#result == null")
    public List<OverspentBudgetDTO> getOverspentBudgets(Double minOverspend, Boolean warningNotSent) {
        if (minOverspend != null && minOverspend < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minOverspend cannot be negative");
        }
        try {
            List<OverspentBudgetProjection> projections = budgetRepository.findOverspentBudgets(minOverspend, warningNotSent);
            return projections.stream().map(p -> new OverspentBudgetDTO.Builder()
                    .budgetId(p.getBudgetId())
                    .userName(p.getUserName())
                    .category(p.getCategory())
                    .budgetAmount(p.getBudgetAmount())
                    .spentAmount(p.getSpentAmount())
                    .overspendPercentage(p.getOverspendPercentage())
                    .warningSent(p.getWarningSent())
                    .build()
            ).toList();
        } catch (Exception e) {
            if (e instanceof ResponseStatusException) throw e;
            log.warn("getOverspentBudgets failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ──────────────────── S4-F1: Active Budget ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F1::' + #userId + '::' + #category",
            unless = "#result == null")
    public Budget getActiveBudgetForUserByCategory(Long userId, Category category) {
        return budgetRepository
                .findActiveBudgetForUserNative(userId, category)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active budget found for this user and category"
                ));
    }

    // ──────────────────── S4-F2: Update Metadata ────────────────────

    @CacheEvict(value = "budget-service", allEntries = true)
    public Budget updateBudgetMetadata(Long budgetId, Map<String, Object> incomingMetadata) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        if (incomingMetadata == null || incomingMetadata.isEmpty()) {
            return budget;
        }

        Map<String, Object> existingMetadata = budget.getMetadata();
        if (existingMetadata == null) {
            existingMetadata = new HashMap<>();
        }

        existingMetadata.putAll(incomingMetadata);

        existingMetadata.putIfAbsent("healthWeight", 1.0);

        // Clamp healthWeight to [0.0, 2.0]
        if (existingMetadata.containsKey("healthWeight")) {
            Object raw = existingMetadata.get("healthWeight");
            double val = 1.0;
            if (raw instanceof Number n) {
                val = n.doubleValue();
            } else if (raw != null) {
                try { val = Double.parseDouble(raw.toString()); } catch (Exception ignored) {}
            }
            existingMetadata.put("healthWeight", Math.min(2.0, Math.max(0.0, val)));
        }

        budget.setMetadata(existingMetadata);
        Budget saved = budgetRepository.save(budget);

        notifyObservers(BudgetEvent.METADATA_UPDATED, Map.of(
                "budgetId", budgetId,
                "updatedKeys", String.join(",", incomingMetadata.keySet())
        ));

        return saved;
    }

    // ──────────────────── S4-F4: Batch Create ────────────────────

    @Transactional
    @CacheEvict(value = "budget-service", allEntries = true)
    public List<Budget> createBudgetsBatch(Long userId, List<Budget> budgets) {
        for (Budget b : budgets) {
            b.setUserId(userId);
            if (b.getStatus() == null) b.setStatus(BudgetStatus.ACTIVE);

            if (b.getMetadata() == null) {
                b.setMetadata(new HashMap<>());
            }

            b.getMetadata().putIfAbsent("healthWeight", 1.0);
        }
        List<Budget> saved = budgetRepository.saveAll(budgets);

        notifyObservers(BudgetEvent.BATCH_CREATED, Map.of(
                "userId", userId != null ? userId : 0L,
                "count", saved.size()
        ));

        return saved;
    }

    // ──────────────────── S4-F8: Metadata Search ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F8::' + #key + '::' + #operator + '::' + #value",
            unless = "#result == null")
    public List<Budget> searchBudgetsByMetadata(String key, String operator, String value) {
        try {
            return budgetRepository.searchBudgetsByMetadata(key, operator, value);
        } catch (Exception e) {
            log.warn("searchBudgetsByMetadata failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ──────────────────── S4-F9: Budget History ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F9::' + #startDate + '::' + #endDate + '::' + #category",
            unless = "#result == null")
    public List<Budget> getBudgetsHistory(LocalDate startDate, LocalDate endDate, String category) {
        try {
            return budgetRepository.findBudgetsInDateRange(
                startDate.atStartOfDay(),
                endDate.atTime(23, 59, 59, 999999999),
                category
            );
        } catch (Exception e) {
            log.warn("getBudgetsHistory failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ──────────────────── S4-F6: Near Limit ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F6::' + #threshold + '::' + (#status != null ? #status.name() : 'ALL')",
            unless = "#result == null")
    public List<BudgetAlertDTO> getBudgetsNearLimit(Double threshold, BudgetStatus status) {
        if (threshold == null || threshold < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threshold must be >= 0");
        }

        double fractionThreshold = threshold >= 1.0 ? threshold / 100.0 : threshold;

        try {
            List<Object[]> rows = budgetRepository.findBudgetsNearLimit(
                    fractionThreshold,
                    status != null ? status.name() : null
            );

            // Collect distinct userIds from the result set
            List<Long> distinctUserIds = rows.stream()
                    .map(row -> ((Number) row[1]).longValue())
                    .distinct()
                    .collect(Collectors.toList());

            // Batch Feign call to user-service for userName resolution
            Map<Long, String> userNameMap = new HashMap<>();
            if (!distinctUserIds.isEmpty()) {
                try {
                    log.info("Calling UserServiceClient.getUsersByIds with ids={}", distinctUserIds);
                    List<UserDTO> users = userServiceClient.getUsersByIds(distinctUserIds);
                    log.info("UserServiceClient.getUsersByIds returned successfully");
                    for (UserDTO user : users) {
                        userNameMap.put(user.getId(), user.getName());
                    }
                } catch (Exception e) {
                    log.warn("Feign call to user-service failed: {}", e.getMessage());
                    // Fallback: use userId as userName
                    for (Long uid : distinctUserIds) {
                        userNameMap.put(uid, String.valueOf(uid));
                    }
                }
            }

            List<BudgetAlertDTO> result = new ArrayList<>();
            for (Object[] row : rows) {
                Long userId = ((Number) row[1]).longValue();
                BudgetAlertDTO dto = new BudgetAlertDTO.Builder()
                        .budgetId(((Number) row[0]).longValue())
                        .userName(userNameMap.getOrDefault(userId, String.valueOf(userId)))
                        .category(Category.valueOf((String) row[2]))
                        .budgetAmount(((Number) row[3]).doubleValue())
                        .spentAmount(((Number) row[4]).doubleValue())
                        .percentUsed(((Number) row[5]).doubleValue())
                        .remainingAmount(((Number) row[6]).doubleValue())
                        .build();
                result.add(dto);
            }
            return result;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException) throw e;
            log.warn("getBudgetsNearLimit failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ──────────────────── Usage Recording (Cassandra) ────────────────────

    @CacheEvict(value = "budget-service", allEntries = true)
    public void recordUsage(Long id, Double spentAmount, String notes) {
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        if (spentAmount == null || spentAmount < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spentAmount must be non-negative");
        }

        double budgetAmount = budget.getAmount() != null ? budget.getAmount() : 0.0;
        double remaining = Math.max(0.0, budgetAmount - spentAmount);
        double percentUsed = budgetAmount > 0 ? (spentAmount / budgetAmount) * 100.0 : 0.0;
        String categoryName = budget.getCategory() != null ? budget.getCategory().name() : null;

        // Write to Cassandra (soft dependency)
        try {
            BudgetUsageEventKey key = new BudgetUsageEventKey(id, Instant.now());
            BudgetUsageEvent usageEvent = new BudgetUsageEvent(key, spentAmount, remaining, percentUsed, categoryName, notes);
            budgetUsageEventRepository.save(usageEvent);
        } catch (Exception e) {
            log.warn("Cassandra write failed for budget usage event [budgetId={}]: {}", id, e.getMessage());
        }

        notifyObservers(BudgetEvent.USAGE_RECORDED, Map.of(
                "budgetId", id,
                "spentAmount", spentAmount,
                "percentUsed", percentUsed,
                "category", categoryName != null ? categoryName : ""
        ));
    }

    // ──────────────────── S4-F12: Budget Usage Timeline ────────────────────

    @Cacheable(value = "budget-service",
            key = "'budget-service::S4-F12::' + #budgetId + '::' + #jwtUid + '::' + #jwtRole + '::' + #startTime + '::' + #endTime",
            unless = "#result == null || #result.isEmpty()")
    public List<BudgetUsageDTO> getBudgetUsageTimeline(Long budgetId, Instant startTime, Instant endTime,
                                                        Long jwtUid, String jwtRole) {
        // 1. Fetch budget from PG
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));

        // 2. Ownership check
        if (budget.getUserId() != null && jwtUid != null
                && !budget.getUserId().equals(jwtUid)
                && !"ADMIN".equalsIgnoreCase(jwtRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have permission to view this budget's usage timeline");
        }

        // 3. Query Cassandra (soft dependency)
        List<BudgetUsageEvent> events;
        try {
            if (startTime != null && endTime != null) {
                events = budgetUsageEventRepository.findByKeyBudgetIdAndKeyTimestampBetween(
                        budgetId, startTime, endTime);
            } else {
                events = budgetUsageEventRepository.findByKeyBudgetId(budgetId);
            }
        } catch (Exception e) {
            log.warn("Cassandra read failed for budget usage timeline [budgetId={}]: {}", budgetId, e.getMessage());
            return new ArrayList<>();  // graceful degradation
        }

        // 4. Adapt results
        List<BudgetUsageDTO> result = new ArrayList<>();
        if (events != null) {
            for (BudgetUsageEvent event : events) {
                result.add(cassandraRowAdapter.adapt(event));
            }
        }
        return result;
    }
}