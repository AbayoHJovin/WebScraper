import java.io.*;
import java.net.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

public class WebsiteDownloader {
    private static final String URL_REGEX = "^(https?://)?([\\w-]+\\.)+[\\w-]+(:\\d+)?(/[^\\s]*)?$";
    private static final String DB_URL = "jdbc:sqlite:website_downloader.db";

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter a valid URL to download:");
        String inputURL = scanner.nextLine();

        if (!isValidURLUsingRegex(inputURL)) {
            System.out.println("Invalid URL format. Exiting.");
            return;
        }

        String domainName = extractDomainName(inputURL);
        File baseFolder = new File(domainName);
        if (!baseFolder.exists() && !baseFolder.mkdir()) {
            System.out.println("Failed to create directory for the website. Exiting.");
            return;
        }

        LocalDateTime startTime = LocalDateTime.now();
        long totalSize = 0;

        try (Connection dbConnection = connectToDatabase()) {
            int websiteId = insertWebsiteRecord(dbConnection, domainName, startTime);

            // Download homepage
            String homepagePath = baseFolder.getPath() + "/index.html";
            downloadFile(inputURL, homepagePath);
            System.out.println("Downloaded homepage to: " + homepagePath);

            // Extract links
            Document doc = Jsoup.parse(new File(homepagePath), "UTF-8");
            Elements links = doc.select("a[href]");

            for (Element link : links) {
                String linkHref = link.attr("abs:href");
                if (!isValidURLUsingRegex(linkHref)) {
                    System.out.println("Invalid link format, skipped: " + linkHref);
                    continue;
                }

                System.out.println("Processing link: " + linkHref);
                long linkStartTime = System.currentTimeMillis();

                // Download link
                String linkFilePath = baseFolder.getPath() + "/" + linkHref.hashCode() + ".html";
                downloadFile(linkHref, linkFilePath);

                long linkElapsedTime = System.currentTimeMillis() - linkStartTime;
                long fileSize = new File(linkFilePath).length() / 1024; // KB
                totalSize += fileSize;

                insertLinkRecord(dbConnection, websiteId, linkHref, linkElapsedTime, fileSize);
                System.out.println("Downloaded: " + linkHref + " (" + fileSize + " KB)");
            }

            LocalDateTime endTime = LocalDateTime.now();
            long totalElapsedTime = Duration.between(startTime, endTime).toMillis();

            updateWebsiteRecord(dbConnection, websiteId, endTime, totalElapsedTime, totalSize);
            System.out.println("Website download completed. Total size: " + totalSize + " KB.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isValidURLUsingRegex(String url) {
        Pattern pattern = Pattern.compile(URL_REGEX, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(url);
        return matcher.matches();
    }

    private static String extractDomainName(String url) {
        try {
            URL netUrl = new URL(url);
            return netUrl.getHost().replaceFirst("www\\.", "");
        } catch (Exception e) {
            return "default";
        }
    }

    private static void downloadFile(String urlString, String savePath) {
        try (BufferedInputStream in = new BufferedInputStream(new URL(urlString).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(savePath)) {

            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        } catch (Exception e) {
            System.err.println("Failed to download: " + urlString);
        }
    }

    private static Connection connectToDatabase() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private static int insertWebsiteRecord(Connection conn, String websiteName, LocalDateTime startTime) throws SQLException {
        String sql = "INSERT INTO Website (website_name, download_start_date_time) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, websiteName);
            pstmt.setString(2, startTime.toString());
            pstmt.executeUpdate();

            ResultSet keys = pstmt.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    private static void insertLinkRecord(Connection conn, int websiteId, String linkName, long elapsedTime, long fileSize) throws SQLException {
        String sql = "INSERT INTO Link (link_name, website_id, total_elapsed_time, total_downloaded_kilobytes) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, linkName);
            pstmt.setInt(2, websiteId);
            pstmt.setLong(3, elapsedTime);
            pstmt.setLong(4, fileSize);
            pstmt.executeUpdate();
        }
    }

    private static void updateWebsiteRecord(Connection conn, int websiteId, LocalDateTime endTime, long totalElapsedTime, long totalSize) throws SQLException {
        String sql = "UPDATE Website SET download_end_date_time = ?, total_elapsed_time = ?, total_downloaded_kilobytes = ? WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, endTime.toString());
            pstmt.setLong(2, totalElapsedTime);
            pstmt.setLong(3, totalSize);
            pstmt.setInt(4, websiteId);
            pstmt.executeUpdate();
        }
    }
}
