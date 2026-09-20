package com.erp.queue_service.handler.store;

import com.erp.core.constants.ReportExportConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra bộ chuẩn hoá bảng kê mệnh giá tiền mặt: đầu vào linh hoạt (object/array/chuỗi số),
 * đầu ra luôn đúng 09 mệnh giá VNĐ giảm dần theo {@link ReportExportConstants#VND_DENOMINATIONS}.
 */
class DenominationParserTest {

    private final DenominationParser parser = new DenominationParser(new ObjectMapper());

    @Test
    @DisplayName("JSON object {mệnh giá: số tờ} → đúng 9 dòng, thứ tự giảm dần, tổng đúng")
    void parse_objectJson_returnsNineDescendingDenominations() {
        List<Map<String, Object>> rows = parser.parse("{\"500000\": 2, \"10000\": 5, \"20000\": 3}");

        assertThat(rows).hasSize(9);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i))
                    .as("dòng %d theo chuẩn VND_DENOMINATIONS", i)
                    .containsEntry("value", ReportExportConstants.VND_DENOMINATIONS.get(i));
        }

        assertThat(rows.get(0).get("count")).isEqualTo(2L); // 500.000 ₫
        assertThat(rows.get(4).get("count")).isEqualTo(3L); // 20.000 ₫
        assertThat(rows.get(5).get("count")).isEqualTo(5L); // 10.000 ₫

        // 500.000*2 + 20.000*3 + 10.000*5 = 1.110.000
        assertThat(parser.total(rows)).isEqualByComparingTo("1110000");
    }

    @Test
    @DisplayName("JSON array hỗ trợ lẫn tên cột denomination/value/menhGia, count/quantity")
    void parse_arrayJson_supportsAliasFields() {
        List<Map<String, Object>> rows = parser.parse(
                "[{\"denomination\": \"100000\", \"count\": 4}, {\"menhGia\": \"50000\", \"quantity\": 1}]");

        assertThat(rows.get(2).get("count")).isEqualTo(4L); // 100.000 ₫
        assertThat(rows.get(3).get("count")).isEqualTo(1L); // 50.000 ₫
        assertThat(rows.get(2)).containsEntry("amount", new BigDecimal("400000"));
    }

    @Test
    @DisplayName("Chuỗi không phải JSON được coi như một số đơn")
    void parse_nonJson_singleNumber() {
        List<Map<String, Object>> rows = parser.parse("50000");
        assertThat(parser.total(rows)).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("Chuỗi rỗng → đủ 9 dòng với số tờ = 0")
    void parse_blank_returnsNineZeroRows() {
        List<Map<String, Object>> rows = parser.parse("   ");
        assertThat(rows).hasSize(9);
        assertThat(parser.total(rows)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("parseAll gộp số tờ cùng mệnh giá qua nhiều phiếu chốt ca")
    void parseAll_mergesCountsAcrossShifts() {
        List<Map<String, Object>> rows = parser.parseAll(
                List.of("{\"500000\": 1}", "{\"100000\": 2, \"500000\": 1}"));

        assertThat(rows.get(0).get("count")).isEqualTo(2L); // 500.000 ₫ cộng dồn
        assertThat(rows.get(2).get("count")).isEqualTo(2L); // 100.000 ₫
    }

    @Test
    @DisplayName("Mệnh giá không hợp lệ bị bỏ qua để giữ đúng 09 mệnh giá chuẩn")
    void parse_unknownDenomination_isIgnored() {
        List<Map<String, Object>> rows = parser.parse("{\"12345\": 3}");
        assertThat(parser.total(rows)).isEqualByComparingTo("0");
    }
}