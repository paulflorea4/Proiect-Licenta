package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** 2.5c: the paging rules every list endpoint shares. */
class PageResponseTests {

    private static final Sort BY_ID = Sort.by("id");

    @Test
    void noParametersMeansTheFirstPageOfTheDefaultSize() {
        var pageable = PageResponse.pageable(null, null, BY_ID);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(BY_ID);
    }

    @Test
    void anOversizedPageIsClampedToTheMaximum() {
        assertThat(PageResponse.pageable(2, 100, BY_ID).getPageSize()).isEqualTo(100);
        assertThat(PageResponse.pageable(2, 101, BY_ID).getPageSize()).isEqualTo(100);
        assertThat(PageResponse.pageable(2, Integer.MAX_VALUE, BY_ID).getPageSize())
                .isEqualTo(100);
        assertThat(PageResponse.pageable(2, 1, BY_ID).getPageSize()).isEqualTo(1);
    }

    @Test
    void theEnvelopeCarriesThePageAndTheTotals() {
        var page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.of(page, n -> "n" + n);

        assertThat(response.items()).containsExactly("n1", "n2");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
