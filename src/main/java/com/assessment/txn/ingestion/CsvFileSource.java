package com.assessment.txn.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a header-based CSV file. Deliberately minimal: no quoted fields (documented limitation). Rows whose
 * column count does not match the header become "malformed" records instead of aborting the batch.
 * The position of a record is its 1-based line number (the header is line 1).
 */
public final class CsvFileSource implements TransactionSource {
    private final Path path;

    public CsvFileSource(Path path) {
        this.path = path;
    }

    @Override
    public Iterable<RawRecord> records() {
        List<RawRecord> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return out;
            }
            if (headerLine.startsWith("﻿")) {
                headerLine = headerLine.substring(1); // strip BOM written by some Windows editors
            }
            String[] header = headerLine.split(",", -1);
            for (int i = 0; i < header.length; i++) {
                header[i] = header[i].trim();
            }
            long lineNo = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                if (cells.length != header.length) {
                    out.add(RawRecord.malformed(lineNo, "row: unexpected number of columns"));
                    continue;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    fields.put(header[i], cells[i].trim());
                }
                out.add(RawRecord.of(lineNo, fields));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read input file", e);
        }
        return out;
    }
}
