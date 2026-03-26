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
    
    // Unicode ranges for Devanagari (Hindi) script
    private static final String HINDI_UNICODE_RANGE = "[\\u0900-\\u097F]";
    
    // Pattern to detect and remove Hindi text
    private static final Pattern HINDI_PATTERN = Pattern.compile(HINDI_UNICODE_RANGE);

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
     * EXTRACT PDF IMMEDIATELY - Called during scraping for each bid
     * Extracts only Pre-Bid Date Time
     * Uses isFinalized flag to skip finalized bids
     */
    @Transactional
    public BidDetails extractBidDetailsImmediately(Bid bid) {
        if (bid == null || bid.getBidDocumentUrl() == null) {
            log.warn("Cannot extract PDF: Bid or URL is null for bid: {}", bid != null ? bid.getBidNumber() : "null");
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
            String pdfText = downloadAndExtractPdfText(pdfUrl, bid.getBidNumber());
            
            if (pdfText != null && !pdfText.isEmpty()) {
                // Filter out Hindi text, keep only English
                String englishText = filterHindiText(pdfText);
                details.setPdfContent(englishText);
                
                // Extract ONLY Pre-Bid Date Time
                LocalDateTime preBidDateTime = extractPreBidDateTime(englishText);
                
                if (preBidDateTime != null) {
                    details.setPreBidDateTime(preBidDateTime);
                    details.setExtractionStatus("COMPLETED");
                    
                    long timeTaken = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("✅ [BID {}] Pre-Bid Date Time extracted: {} in {}s", 
                             bid.getBidNumber(), preBidDateTime, timeTaken);
                } else {
                    details.setExtractionStatus("COMPLETED"); // Still mark as completed even if no pre-bid date
                    log.info("   [BID {}] No pre-bid date time found in PDF", bid.getBidNumber());
                }
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

    /**
     * Filter out Hindi text, keep only English content
     */
    private String filterHindiText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String[] lines = text.split("\\r?\\n");
        StringBuilder englishOnly = new StringBuilder();
        
        for (String line : lines) {
            Matcher hindiMatcher = HINDI_PATTERN.matcher(line);
            if (!hindiMatcher.find()) {
                // No Hindi in this line, keep it
                englishOnly.append(line).append("\n");
            } else {
                // Line has Hindi, try to extract only English parts after "/"
                String[] parts = line.split("/");
                for (String part : parts) {
                    if (!HINDI_PATTERN.matcher(part).find()) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && trimmed.length() > 2) {
                            englishOnly.append(trimmed).append(" ");
                        }
                    }
                }
                englishOnly.append("\n");
            }
        }
        
        return englishOnly.toString().trim();
    }

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
                    Thread.sleep(3000 * attempt);
                }
                
                URL url = new URL(pdfUrl);
                URLConnection connection = url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
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
     * Extract ONLY Pre-Bid Date and Time
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
        
        // Try pattern1 first (most specific to your PDFs with Hindi)
        Matcher matcher1 = pattern1.matcher(cleanText);
        if (matcher1.find()) {
            try {
                String dateStr = matcher1.group(1).replace("/", "-");
                String timeStr = matcher1.group(2);
                String dateTimeStr = dateStr + " " + timeStr;
                
                LocalDateTime dateTime = parseDateTimeFlexible(dateTimeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern1: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern1: {}", e.getMessage());
            }
        }
        
        // Try pattern2
        Matcher matcher2 = pattern2.matcher(cleanText);
        if (matcher2.find()) {
            try {
                String dateStr = matcher2.group(1).replace("/", "-");
                String timeStr = matcher2.group(2);
                String dateTimeStr = dateStr + " " + timeStr;
                
                LocalDateTime dateTime = parseDateTimeFlexible(dateTimeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern2: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern2: {}", e.getMessage());
            }
        }
        
        // Try pattern3
        Matcher matcher3 = pattern3.matcher(cleanText);
        if (matcher3.find()) {
            try {
                String dateStr = matcher3.group(1).replace("/", "-");
                String timeStr = matcher3.group(2);
                String dateTimeStr = dateStr + " " + timeStr;
                
                LocalDateTime dateTime = parseDateTimeFlexible(dateTimeStr);
                if (dateTime != null) {
                    log.debug("Extracted pre-bid date using pattern3: {}", dateTime);
                    return dateTime;
                }
            } catch (Exception e) {
                log.debug("Failed to parse pattern3: {}", e.getMessage());
            }
        }
        
        // Try pattern4
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
     * Check if text contains pre-bid related keywords
     */
    private boolean containsPreBidKeywords(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("pre-bid") || 
               lowerText.contains("prebid") || 
               lowerText.contains("pre bid") ||
               lowerText.contains("pre-bidding") ||
               lowerText.contains("pre bidding") ||
               lowerText.contains("मूल्य मिन्नता खंड दस्तावेज") || // Hindi for Pre-Bid
               lowerText.contains("pre bid detail") || // Common in PDFs
               text.contains("Pre Bid Detail(s)"); // Exact match from your PDFs
    }

    /**
     * Flexible date parser that tries multiple formats
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
                    // Try without seconds
                    if (dateTimeStr.length() > 16) {
                        String withoutSeconds = dateTimeStr.substring(0, 16);
                        return LocalDateTime.parse(withoutSeconds, DATE_FORMATTER_ALT);
                    }
                } catch (Exception e3) {
                    // Ignore
                }
                
                try {
                    // Try with different separators
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
}