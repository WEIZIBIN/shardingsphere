/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sharding.strategy.algorithm.sharding;

import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.Setter;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalField;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Datetime sharding algorithm that adapt various shard method by define properties below.
 *
 * <p>properties defined here:
 *
 * <p>datetime.format: the datetime format used by applications, must can be transformed to {@link LocalDateTime},
 * used by {@link LocalDateTime#parse(CharSequence, DateTimeFormatter)}.
 *
 * <p>table.suffix.format: suffix for sharded tables, used by {@link LocalDateTime#format(DateTimeFormatter)},
 * examples:
 * suffix=yyyyQQ means shard by {@link IsoFields#QUARTER_OF_YEAR};
 * suffix=yyyyMM means shard by {@link ChronoUnit#MONTHS};
 * suffix=yyyyMMdd means shard by {@link ChronoField#DAY_OF_YEAR}.
 *
 * <p>detail explain for each char in datetime.format and table.suffix.format can refer {@link TemporalField}.
 *
 * <p>datetime.lower and datetime.upper: if app query with only half bound, lower and upper helps to build other half bound,
 * datetime.lower must be specified and datetime.upper has a default value to {@link LocalDateTime#now}
 * (default value of datetime.upper could only be used when query sql needn't get result that time larger than query time).
 *
 * <p>datetime.step.unit and datetime.step.amount used for calculate tables for range shard, datetime.step.unit is name of
 * {@link ChronoUnit}, default unit is Days and amount is 1, amount + unit should not be larger than but close to your shard range.
 *
 * <p>examples: when shard by {@link IsoFields#QUARTER_OF_YEAR}, datetime.step.unit = Months and datetime.step.amount = 3 is a better choice.
 */
public final class CustomDateTimeShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {
    
    private static final String DATE_TIME_FORMAT = "datetime.format";
    
    private static final String TABLE_SUFFIX_FORMAT = "table.suffix.format";
    
    private static final String DEFAULT_LOWER = "datetime.lower";
    
    private static final String DEFAULT_UPPER = "datetime.upper";
    
    private static final String STEP_UNIT = "datetime.step.unit";
    
    private static final String STEP_AMOUNT = "datetime.step.amount";
    
    private DateTimeFormatter datetimeFormatter;
    
    private ChronoUnit stepUnit;
    
    private int stepAmount;
    
    @Getter
    @Setter
    private Properties props = new Properties();
    
    @Override
    public void init() {
        Preconditions.checkNotNull(props.getProperty(DATE_TIME_FORMAT));
        Preconditions.checkNotNull(props.getProperty(TABLE_SUFFIX_FORMAT));
        Preconditions.checkNotNull(props.getProperty(DEFAULT_LOWER));
        stepUnit = null == props.getProperty(STEP_UNIT) ? ChronoUnit.DAYS : generateStepUnit();
        stepAmount = Integer.parseInt(props.getProperty(STEP_AMOUNT, "1"));
        datetimeFormatter = DateTimeFormatter.ofPattern(props.getProperty(DATE_TIME_FORMAT));
        try {
            parseDateTimeForValue(props.getProperty(DEFAULT_LOWER));
            if (props.getProperty(DEFAULT_UPPER) != null) {
                parseDateTimeForValue(props.getProperty(DEFAULT_UPPER));
            }
        } catch (DateTimeParseException e) {
            throw new UnsupportedOperationException("can't apply shard value for default lower/upper values", e);
        }
    }
    
    @Override
    public String doSharding(final Collection<String> availableTargetNames, final PreciseShardingValue<Comparable<?>> shardingValue) {
        return availableTargetNames.stream()
                .filter(tableName -> tableName.endsWith(formatForDateTime(parseDateTimeForValue(shardingValue.getValue().toString()))))
                .findFirst().orElseThrow(() -> new UnsupportedOperationException(
                        String.format("failed to shard value %s, and availableTables %s", shardingValue, availableTargetNames)));
    }
    
    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames, final RangeShardingValue<Comparable<?>> shardingValue) {
        boolean hasStart = shardingValue.getValueRange().hasLowerBound();
        boolean hasEnd = shardingValue.getValueRange().hasUpperBound();
        Set<String> tables = new HashSet<>();
        if (!hasStart && !hasEnd) {
            return availableTargetNames;
        }
        LocalDateTime start = hasStart ? parseDateTimeForValue(shardingValue.getValueRange().lowerEndpoint().toString()) : parseDateTimeForValue(props.getProperty(DEFAULT_LOWER));
        LocalDateTime end = hasEnd
                ? parseDateTimeForValue(shardingValue.getValueRange().upperEndpoint().toString())
                : props.getProperty(DEFAULT_UPPER) == null
                ? LocalDateTime.now()
                : parseDateTimeForValue(props.getProperty(DEFAULT_UPPER));
        LocalDateTime tmp = start;
        while (!tmp.isAfter(end)) {
            mergeTableIfMatch(tmp, tables, availableTargetNames);
            tmp = tmp.plus(stepAmount, stepUnit);
        }
        mergeTableIfMatch(end, tables, availableTargetNames);
        return tables;
    }
    
    private LocalDateTime parseDateTimeForValue(final String value) {
        return LocalDateTime.parse(value.substring(0, props.getProperty(DATE_TIME_FORMAT).length()), datetimeFormatter);
    }
    
    private String formatForDateTime(final LocalDateTime localDateTime) {
        return localDateTime.format(DateTimeFormatter.ofPattern(props.get(TABLE_SUFFIX_FORMAT).toString()));
    }
    
    private void mergeTableIfMatch(final LocalDateTime dateTime, final Collection<String> tables, final Collection<String> availableTargetNames) {
        String suffix = formatForDateTime(dateTime);
        availableTargetNames.parallelStream().filter(tableName -> tableName.endsWith(suffix)).findAny().map(tables::add);
    }
    
    private ChronoUnit generateStepUnit() {
        for (ChronoUnit unit : ChronoUnit.values()) {
            if (unit.toString().equalsIgnoreCase(props.getProperty(STEP_UNIT))) {
                return unit;
            }
        }
        throw new UnsupportedOperationException(
                String.format("can't find step unit for specified datetime.step.unit prop: %s", props.getProperty(STEP_UNIT)));
    }
    
    @Override
    public String getType() {
        return "CUSTOM_DATE_TIME";
    }
}