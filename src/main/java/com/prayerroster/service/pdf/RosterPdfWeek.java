package com.prayerroster.service.pdf;

import java.util.List;

public record RosterPdfWeek(String weekLabel, List<RosterPdfRow> rows) {
}
