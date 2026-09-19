package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory quản lý các chiến lược xuất tệp báo cáo trong queue-service.
 */
@Component
public class ExportStrategyFactory {

    private final Map<ExportFormat, ExportStrategy> strategies = new EnumMap<>(ExportFormat.class);

    public ExportStrategyFactory(List<ExportStrategy> strategyList) {
        for (ExportStrategy strategy : strategyList) {
            strategies.put(strategy.getSupportedFormat(), strategy);
        }
    }

    public ExportStrategy getStrategy(ExportFormat format) {
        if (format == null) {
            format = ExportFormat.EXCEL;
        }
        ExportStrategy strategy = strategies.get(format);
        if (strategy == null) {
            throw new IllegalArgumentException("Không hỗ trợ định dạng: " + format);
        }
        return strategy;
    }
}
