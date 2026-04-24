package in.BidPilots.service;

import in.BidPilots.entity.Bid;
import in.BidPilots.entity.BidDetails;
import in.BidPilots.repository.BidDetailsRepository;
import in.BidPilots.repository.BidRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class BidDetailsService {

    private final BidDetailsRepository bidDetailsRepository;
    private final BidRepository bidRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER_ALT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    // ─────────────────────────────────────────────────────────────────────────
    // Item Category extraction patterns
    //
    // GeM PDFs always have a bilingual header row that looks like one of:
    //   "Item Category"                          (English-only table header)
    //   "Item Category / वस्तु श्रेणी"           (bilingual)
    //   "वस्तु श्रेणी /Item Category"             (Hindi first)
    //
    // After the header comes the actual value cell, separated by a newline
    // (table layout) or occasionally on the same line after the label.
    //
    // Strategy:
    //   1. Work on the RAW (unfiltered) text so Hindi in the value is kept.
    //   2. Locate the header with a flexible pattern.
    //   3. Capture everything after the header up to the NEXT known section
    //      header (e.g. "GeMARPTS", "MSE", "Startup", "Evaluation", etc.)
    //      OR up to a blank line run that signals a table row break.
    //   4. Clean / normalise the captured value.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Matches the "Item Category" table header in GeM PDFs.
     * Handles:
     *   • English-only: "Item Category"
     *   • Bilingual (English first): "Item Category / वस्तु श्रेणी"
     *   • Bilingual (Hindi first): "वस्तु श्रेणी /Item Category"
     * The (?i) flag makes it case-insensitive.
     */
    private static final Pattern ITEM_CATEGORY_HEADER = Pattern.compile(
        "(?i)" +
        "(?:" +
            // Hindi label optionally before the slash
            "[\\u0900-\\u097F\\s]*" +
            "(?:/\\s*)?" +
        ")?" +
        "Item\\s+Category" +
        // optional bilingual suffix after
        "(?:\\s*/\\s*[\\u0900-\\u097F\\s/]*)?"
    );

    /**
     * Section headers that immediately follow the Item Category value in GeM PDFs.
     * When we see any of these we know the value has ended.
     *
     * Covers English and common Hindi headings.
     */
    private static final Pattern NEXT_SECTION_HEADER = Pattern.compile(
        "(?i)" +
        "(?:" +
            "GeMARPTS" +
            "|MSE\\s+Relaxation" +
            "|Startup\\s+Relaxation" +
            "|Relevant\\s+Categor" +
            "|Evaluation\\s+Method" +
            "|Inspection\\s+Required" +
            "|Arbitration" +
            "|Mediation" +
            "|EMD\\s+Detail" +
            "|ePBG\\s+Detail" +
            "|MII\\s+Purchase" +
            "|Document\\s+required" +
            "|Bid\\s+splitting" +
            "|Primary\\s+product" +
            "|Technical\\s+Clarification" +
            "|Type\\s+of\\s+Bid" +
            "|Consignee" +
            // Hindi variants
            "|[\\u0900-\\u097F]{4,}" +   // 4+ consecutive Devanagari chars = likely a Hindi heading
            "|GeMARPTS\\s+म" +
        ")"
    );

    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void initOnStartup() {
        log.info("🔍 Checking for bids without details on startup...");

        new Thread(() -> {
            try {
                initializeBidDetails();
                log.info("✅ Startup initialization complete");
            } catch (Exception e) {
                log.error("Error in startup initialization: {}", e.getMessage(), e);
            }
        }).start();
    }

    @Transactional
    public void initializeBidDetails() {
        List<Bid> bids = bidRepository.findAll();
        int created = 0;

        for (Bid bid : bids) {
            if (!bidDetailsRepository.existsByBid(bid)) {
                createBidDetailsForBid(bid);
                created++;
            }
        }

        if (created > 0) {
            log.info("✅ Initialized bid details for {} bids", created);
        } else {
            log.info("✅ All bids already have details entries");
        }
    }

    @Transactional
    public BidDetails createBidDetailsForBid(Bid bid) {
        if (bidDetailsRepository.existsByBid(bid)) {
            return bidDetailsRepository.findByBid(bid).orElse(null);
        }

        BidDetails details = new BidDetails();
        details.setBid(bid);
        details.setExtractionStatus("PENDING");

        BidDetails savedDetails = bidDetailsRepository.save(details);
        log.debug("✅ Created BidDetails for bid: {}", bid.getBidNumber());

        return savedDetails;
    }

    /**
     * EXTRACT PDF IMMEDIATELY - Called during scraping for each bid.
     * Extracts:
     *   1. Pre-Bid Date Time  (existing)
     *   2. Item Category      (NEW — works on raw text before Hindi filtering)
     *
     * Uses isFinalized flag to skip finalized bids.
     * All other services (GeMScrapingService, AsyncScrapingService) are unaffected.
     */
    @Transactional
    public BidDetails extractBidDetailsImmediately(Bid bid) {
        if (bid == null || bid.getBidDocumentUrl() == null) {
            log.warn("Cannot extract PDF: Bid or URL is null for bid: {}",
                    bid != null ? bid.getBidNumber() : "null");
            return null;
        }

        // Skip extraction if bid is finalized (no need to process old bids)
        if (Boolean.TRUE.equals(bid.getIsFinalized())) {
            log.debug("⏭️ Bid {} is finalized, skipping PDF extraction", bid.getBidNumber());
            return null;
        }

        long startTime = System.currentTimeMillis();

        BidDetails details = bidDetailsRepository.findByBid(bid).orElseGet(() -> {
            BidDetails newDetails = new BidDetails();
            newDetails.setBid(bid);
            newDetails.setExtractionStatus("PENDING");
            return newDetails;
        });

        if ("COMPLETED".equals(details.getExtractionStatus())) {
            log.debug("⏭️ Bid {} already completed, skipping", bid.getBidNumber());
            return details;
        }

        details.setExtractionStatus("PROCESSING");
        bidDetailsRepository.save(details);
        bidDetailsRepository.flush();

        String pdfUrl = bid.getFullBidDocumentUrl();
        log.info("📄 [BID {}] Extracting PDF immediately from: {}", bid.getBidNumber(), pdfUrl);

        try {
            // ── Step 1: Download raw text (contains both Hindi and English) ──
            String rawPdfText = downloadAndExtractPdfText(pdfUrl, bid.getBidNumber());

            if (rawPdfText != null && !rawPdfText.isEmpty()) {

                // ── Step 2a: Persist the full raw PDF text ──
                //    Stored in LONGTEXT column; deleted automatically when the bid is finalized.
                details.setPdfContent(rawPdfText);

                // ── Step 2b: Extract Item Category from RAW text (before Hindi filter) ──
                //    This preserves the English item names which may sit alongside
                //    Hindi column headers in the PDF table layout.
                String itemCategory = extractItemCategory(rawPdfText, bid.getBidNumber());
                if (itemCategory != null && !itemCategory.isEmpty()) {
                    details.setItemCategory(itemCategory);
                    log.info("📦 [BID {}] Item Category extracted: {}", bid.getBidNumber(),
                            itemCategory.length() > 120
                                    ? itemCategory.substring(0, 120) + "…"
                                    : itemCategory);
                } else {
                    log.info("   [BID {}] No Item Category found in PDF", bid.getBidNumber());
                }

                // ── Step 3: Extract Pre-Bid Date Time ──
                // Run on raw text so Hindi-interleaved date patterns are still matched.
                LocalDateTime preBidDateTime = extractPreBidDateTime(rawPdfText);
                if (preBidDateTime != null) {
                    details.setPreBidDateTime(preBidDateTime);
                    long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("✅ [BID {}] Pre-Bid Date Time extracted: {} in {}s",
                            bid.getBidNumber(), preBidDateTime, timeTaken);
                } else {
                    log.info("   [BID {}] No pre-bid date time found in PDF", bid.getBidNumber());
                }

                details.setExtractionStatus("COMPLETED");

            } else {
                details.setExtractionStatus("FAILED");
                log.warn("⚠️ [BID {}] No text extracted from PDF", bid.getBidNumber());
            }

            return bidDetailsRepository.save(details);

        } catch (Exception e) {
            log.error("❌ [BID {}] PDF extraction failed: {}", bid.getBidNumber(), e.getMessage());
            details.setExtractionStatus("FAILED");
            return bidDetailsRepository.save(details);
        }
    }

    // =========================================================================
    //  ITEM CATEGORY EXTRACTION
    // =========================================================================

    /**
     * Extracts the "Item Category" section from raw GeM PDF text.
     *
     * <p>The method is designed to handle every real-world format seen in GeM PDFs:
     * <ul>
     *   <li>Single-line value on the same line as the header</li>
     *   <li>Value on the next line (normal table layout)</li>
     *   <li>Multi-line / multi-item values that span many lines
     *       (e.g. 100 comma-separated item names across many wrapped rows)</li>
     *   <li>Mixed Hindi+English column headers — the method works on raw text
     *       before any Hindi filtering, so English item names are never lost</li>
     * </ul>
     *
     * <p>Termination: capture stops when a recognised "next section" header
     * appears, or when two consecutive blank lines are found (signals a PDF
     * table-row boundary that is not part of the item list).
     *
     * @param rawText  raw text extracted from the PDF (Hindi + English mixed)
     * @param bidNumber bid number used only for logging
     * @return cleaned item category string, or {@code null} if not found
     */
    private String extractItemCategory(String rawText, String bidNumber) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }

        String[] lines = rawText.split("\\r?\\n");

        int headerLine = -1;    // index of the line that contains the header
        int valueStartCol = 0;  // character offset within headerLine after the header

        // ── Phase 1: Find the header line ──────────────────────────────────
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ITEM_CATEGORY_HEADER.matcher(lines[i]);
            if (m.find()) {
                headerLine = i;
                valueStartCol = m.end();
                break;
            }
        }

        if (headerLine == -1) {
            log.debug("   [BID {}] Item Category header not found in PDF", bidNumber);
            return null;
        }

        // ── Phase 2: Collect value lines ───────────────────────────────────
        StringBuilder collected = new StringBuilder();

        // Check if there is an inline value on the SAME line as the header
        String remainder = lines[headerLine].substring(valueStartCol).trim();
        // Strip any trailing Hindi label that may follow the English header
        // e.g. "Item Category / वस्तु श्रेणी"  → remainder is empty
        remainder = stripLeadingHindiLabel(remainder);
        if (!remainder.isEmpty() && !looksLikeNextSection(remainder)) {
            collected.append(remainder);
        }

        // Now look forward through subsequent lines
        int consecutiveBlankLines = 0;

        for (int i = headerLine + 1; i < lines.length; i++) {
            String line = lines[i].trim();

            // Empty line handling: two empty lines in a row = section boundary
            if (line.isEmpty()) {
                consecutiveBlankLines++;
                if (consecutiveBlankLines >= 2) {
                    break;
                }
                // Single blank line may just be a wrapped row gap — keep going
                // but only add a separator if we already have content
                if (collected.length() > 0) {
                    // Don't append blank; just continue accumulation
                }
                continue;
            }
            consecutiveBlankLines = 0;

            // Stop if we hit a new section header
            if (looksLikeNextSection(line)) {
                break;
            }

            // Some PDFs have a repeated bilingual label on a separate line
            // (artefact of PDF table rendering). Skip such lines so we don't
            // include the label text in the value.
            if (ITEM_CATEGORY_HEADER.matcher(line).find() && collected.length() == 0) {
                // This is just the second half of a split bilingual header — skip it
                continue;
            }

            // Append the line to our collected value
            if (collected.length() > 0) {
                collected.append(", ");
            }
            collected.append(line);
        }

        if (collected.length() == 0) {
            return null;
        }

        return cleanItemCategory(collected.toString());
    }

    /**
     * If the string starts with a Hindi label (possibly followed by a slash),
     * strip that prefix and return the remainder.
     * Used when the header and value appear on the same line.
     */
    private String stripLeadingHindiLabel(String text) {
        if (text == null || text.isEmpty()) return text;

        // Remove leading Hindi chars and slashes/spaces
        // Pattern: optional Hindi block + optional slash → trim
        String stripped = text.replaceFirst("^[\\u0900-\\u097F\\s/]+", "").trim();
        return stripped;
    }

    /**
     * Returns true if the line looks like a section header that follows the
     * Item Category block — signals that we must stop collecting.
     */
    private boolean looksLikeNextSection(String line) {
        return NEXT_SECTION_HEADER.matcher(line).find();
    }

    /**
     * Normalise the raw collected item category text:
     * <ul>
     *   <li>Collapse runs of whitespace / commas into a single ", " delimiter</li>
     *   <li>Remove stray punctuation left over from PDF table borders</li>
     *   <li>Trim leading/trailing whitespace</li>
     * </ul>
     */
    private String cleanItemCategory(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // Collapse multiple commas / whitespace runs
        String cleaned = raw
                .replaceAll("[,\\s]+,", ",")          // whitespace before comma
                .replaceAll(",\\s*,", ",")              // double commas
                .replaceAll("\\s{2,}", " ")             // multiple spaces → one
                .replaceAll("^[,\\s]+", "")             // leading comma/space
                .replaceAll("[,\\s]+$", "")             // trailing comma/space
                .trim();

        // If the whole thing is just punctuation / numbers, discard
        if (cleaned.replaceAll("[^a-zA-Z\\u0900-\\u097F]", "").isEmpty()) {
            return null;
        }

        return cleaned;
    }

    // =========================================================================
    //  EXISTING METHODS — UNCHANGED
    // =========================================================================

    @Async
    @Transactional
    public CompletableFuture<BidDetails> extractBidDetailsAsync(Long bidId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<Bid> bidOpt = bidRepository.findById(bidId);
                if (bidOpt.isPresent()) {
                    Bid bid = bidOpt.get();
                    // Skip if finalized
                    if (Boolean.TRUE.equals(bid.getIsFinalized())) {
                        log.debug("⏭️ Bid {} is finalized, skipping async extraction", bid.getBidNumber());
                        return null;
                    }
                    return extractBidDetailsImmediately(bid);
                }
                return null;
            } catch (Exception e) {
                log.error("❌ Error in async extraction for bid ID {}: {}", bidId, e.getMessage());
                return null;
            }
        });
    }

    private String downloadAndExtractPdfText(String pdfUrl, String bidNumber) {
        int maxAttempts = 2;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("🔄 [BID {}] Retry attempt {}/{} for PDF download", bidNumber, attempt + 1, maxAttempts);
                    Thread.sleep(3000L * attempt);
                }

                URL url = new URL(pdfUrl);
                URLConnection connection = url.openConnection();
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);

                try (InputStream is = connection.getInputStream()) {
                    PDDocument document = Loader.loadPDF(is.readAllBytes());
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(document);
                    document.close();
                    return text;
                }
            } catch (SocketTimeoutException e) {
                log.warn("⏱️ [BID {}] Socket timeout on attempt {}/{}", bidNumber, attempt + 1, maxAttempts);
            } catch (Exception e) {
                log.error("❌ [BID {}] Error downloading PDF on attempt {}/{}: {}",
                        bidNumber, attempt + 1, maxAttempts, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Extract ONLY Pre-Bid Date and Time (unchanged from original).
     */
    private LocalDateTime extractPreBidDateTime(String text) {
        // Clean the text - remove extra spaces
        String cleanText = text.replaceAll("\\s+", " ").trim();

        // Check if text contains pre-bid keywords, if not return null
        if (!containsPreBidKeywords(cleanText)) {
            return null;
        }

        // Pattern 1: Look for the exact format with Hindi headers (from your PDFs)
        Pattern pattern1 = Pattern.compile(
            "मूल्य मिन्नता खंड दस्तावेज[ /]*Pre-Bid Date and Time\\s*[\\D]*(\\d{2}[-/]\\d{2}[-/]\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE
        );

        // Pattern 2: Pre Bid Detail(s) section with date
        Pattern pattern2 = Pattern.compile(
            "Pre Bid Detail\\(s\\)[^\\d]*?(\\d{2}[-/]\\d{2}[-/]\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE
        );

        // Pattern 3: Standard "Pre-Bid Date and Time" format
        Pattern pattern3 = Pattern.compile(
            "Pre[\\s-]*Bid[^\\d]*?(\\d{2}[-/]\\d{2}[-/]\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE
        );

        // Pattern 4: मूल्य मिन्नता खंड दस्तावेज/Pre-Bid Date and Time (table format)
        Pattern pattern4 = Pattern.compile(
            "मूल्य मिन्नता खंड दस्तावेज/Pre-Bid Date and Time\\s*([\\d-]+\\s+[\\d:]+)",
            Pattern.CASE_INSENSITIVE
        );

        Matcher matcher1 = pattern1.matcher(cleanText);
        if (matcher1.find()) {
            try {
                String dateStr = matcher1.group(1).replace("/", "-");
                String timeStr = matcher1.group(2);
                LocalDateTime dateTime = parseDateTimeFlexible(dateStr + " " + timeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern1: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern1: {}", e.getMessage());
            }
        }

        Matcher matcher2 = pattern2.matcher(cleanText);
        if (matcher2.find()) {
            try {
                String dateStr = matcher2.group(1).replace("/", "-");
                String timeStr = matcher2.group(2);
                LocalDateTime dateTime = parseDateTimeFlexible(dateStr + " " + timeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern2: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern2: {}", e.getMessage());
            }
        }

        Matcher matcher3 = pattern3.matcher(cleanText);
        if (matcher3.find()) {
            try {
                String dateStr = matcher3.group(1).replace("/", "-");
                String timeStr = matcher3.group(2);
                LocalDateTime dateTime = parseDateTimeFlexible(dateStr + " " + timeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern3: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern3: {}", e.getMessage());
            }
        }

        Matcher matcher4 = pattern4.matcher(cleanText);
        if (matcher4.find()) {
            try {
                String dateTimeStr = matcher4.group(1).trim().replace("/", "-");
                LocalDateTime dateTime = parseDateTimeFlexible(dateTimeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern4: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern4: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Check if text contains pre-bid related keywords.
     */
    private boolean containsPreBidKeywords(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("pre-bid") ||
               lowerText.contains("prebid") ||
               lowerText.contains("pre bid") ||
               lowerText.contains("pre-bidding") ||
               lowerText.contains("pre bidding") ||
               lowerText.contains("मूल्य मिन्नता खंड दस्तावेज") ||
               lowerText.contains("pre bid detail") ||
               text.contains("Pre Bid Detail(s)");
    }

    /**
     * Flexible date parser that tries multiple formats.
     */
    private LocalDateTime parseDateTimeFlexible(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr, DATE_FORMATTER);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(dateTimeStr, DATE_FORMATTER_ALT);
            } catch (DateTimeParseException e2) {
                try {
                    if (dateTimeStr.length() > 16) {
                        String withoutSeconds = dateTimeStr.substring(0, 16);
                        return LocalDateTime.parse(withoutSeconds, DATE_FORMATTER_ALT);
                    }
                } catch (Exception e3) {
                    // Ignore
                }

                try {
                    String cleaned = dateTimeStr.replace("/", "-").trim();
                    return LocalDateTime.parse(cleaned, DATE_FORMATTER);
                } catch (Exception e4) {
                    log.debug("Failed to parse date: {}", dateTimeStr);
                }
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return bidDetailsRepository.countByExtractionStatus(status);
    }

    @Transactional(readOnly = true)
    public List<BidDetails> getPendingExtractions() {
        return bidDetailsRepository.findPendingExtractions();
    }

    @Transactional(readOnly = true)
    public List<BidDetails> getByStatus(String status) {
        return bidDetailsRepository.findByExtractionStatus(status);
    }
    // =========================================================================
    //  FINALIZATION CLEANUP — called by BidAutoCloseService
    // =========================================================================

    /**
     * Deletes all {@link BidDetails} rows for bids that have just been finalized.
     *
     * <p>This is invoked automatically by {@link BidAutoCloseService#finalizeOldBidsNow()}
     * immediately after the bulk UPDATE that sets {@code is_finalized = true}.
     * Removing these rows keeps the {@code bid_details} table lean — old closed bids
     * no longer need their PDF content, pre-bid dates, or item-category cache.</p>
     *
     * @return number of {@code bid_details} rows deleted
     */
    @Transactional
    public int deleteDetailsForFinalizedBids() {
        long count = bidDetailsRepository.countDetailsForFinalizedBids();
        if (count == 0) {
            return 0;
        }
        int deleted = bidDetailsRepository.deleteDetailsForFinalizedBids();
        log.info("🗑️  Deleted {} bid_details rows for finalized bids", deleted);
        return deleted;
    }

    /**
     * Deletes the {@link BidDetails} row for a single finalized bid.
     * Useful for manual / admin triggers.
     *
     * @param bid the bid whose details should be removed
     */
    @Transactional
    public void deleteDetailsForBid(Bid bid) {
        bidDetailsRepository.findByBid(bid).ifPresent(details -> {
            bidDetailsRepository.delete(details);
            log.info("🗑️  Deleted bid_details for finalized bid: {}", bid.getBidNumber());
        });
    }

}