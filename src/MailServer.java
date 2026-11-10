import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MAIL SERVER (Lab 3)
 *  - SMTP  : cổng 2525 (nhận thư từ client, lưu vào hộp thư của người nhận @localhost)
 *  - POP3  : cổng 1100 (client đăng nhập USER/PASS rồi STAT/LIST/RETR/DELE/TOP/UIDL/QUIT)
 *  - Database : SQLite (tùy chọn). Có sqlite-jdbc.jar trong classpath -> lưu user + thư vào mail.db.
 *               Không có -> lưu tạm trong RAM (mất khi tắt server).
 *  - Đăng nhập POP3 lần đầu với 1 tên mới = tự đăng ký tài khoản (giống ChatServer).
 *  - Thư gửi ra Gmail: dùng MailClient kết nối thẳng smtp.gmail.com (server này chỉ lo thư @localhost).
 *  - Giao diện: mở cửa sổ theo dõi (nhật ký, tài khoản, thư đã lưu). Thêm --nogui để chạy thuần console như cũ.
 *
 * Biên dịch : javac -encoding UTF-8 MailServer.java
 * Chạy      : java MailServer                         (không DB)
 *             java -cp ".;sqlite-jdbc.jar" MailServer (có DB; Linux/Mac dùng ':' thay ';')
 *             java MailServer --nogui                 (không mở cửa sổ)
 * (Cổng 25/110 chuẩn cần quyền admin nên dùng 2525/1100.)
 */
public class MailServer {
    static final int SMTP_PORT = 2525, POP3_PORT = 1100;
    static final String DOMAIN = "localhost";
    static final int MAX_MAIL = 5 * 1024 * 1024;

    static Connection db;
    static final Map<String, String> memUsers = new ConcurrentHashMap<>();
    static final List<Mail> memMails = new CopyOnWriteArrayList<>();
    static final AtomicLong memId = new AtomicLong();

    // nhật ký cho giao diện
    static final List<String> logBuf = new CopyOnWriteArrayList<>();
    static volatile Consumer<String> uiLog;

    static class Mail {
        final long id;
        final String owner, sender, raw, time;

        Mail(long id, String owner, String sender, String raw, String time) {
            this.id = id;
            this.owner = owner;
            this.sender = sender;
            this.raw = raw;
            this.time = time;
        }

        int size() {
            return raw.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    interface Session {
        void run(Socket s) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        boolean gui = !GraphicsEnvironment.isHeadless() && !Arrays.asList(args).contains("--nogui");
        if (gui) {
            try {
                System.setProperty("awt.useSystemAAFontSettings", "on");
                System.setProperty("swing.aatext", "true");
                UIManager.put("swing.boldMetal", Boolean.FALSE);
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                for (Object k : Collections.list(UIManager.getDefaults().keys()))
                    if (k.toString().endsWith(".font"))
                        UIManager.put(k, new javax.swing.plaf.FontUIResource(f(Font.PLAIN, 13)));
            } catch (Exception ignored) {
            }
        }
        initDb();
        listen(SMTP_PORT, "SMTP", MailServer::smtp);
        listen(POP3_PORT, "POP3", MailServer::pop3);
        log("Mail server chạy: SMTP " + SMTP_PORT + " | POP3 " + POP3_PORT + " | domain @" + DOMAIN);
        if (gui) SwingUtilities.invokeLater(() -> new Gui().setVisible(true));
    }

    static void listen(int port, String name, Session h) throws IOException {
        ServerSocket ss = new ServerSocket(port);
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    pool.execute(() -> {
                        try {
                            s.setSoTimeout(5 * 60 * 1000);
                            h.run(s);
                        } catch (IOException ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        }, name + "-accept").start();
    }

    // ------------------------------------------------------------------ DATABASE
    static void initDb() {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:mail.db");
            try (Statement st = db.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, password TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS mails(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "owner TEXT, sender TEXT, raw TEXT, time TEXT)");
            }
            log("Database SQLite: BẬT (mail.db)");
        } catch (Throwable e) {
            db = null;
            log("Không tìm thấy sqlite-jdbc -> chạy KHÔNG database (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    /** null = OK. Tên chưa có thì tự đăng ký. */
    static synchronized String auth(String u, String p) {
        String h = hash(p);
        if (db == null) {
            String old = memUsers.putIfAbsent(u, h);
            return old == null || old.equals(h) ? null : "Sai mat khau";
        }
        try (PreparedStatement ps = db.prepareStatement("SELECT password FROM users WHERE username=?")) {
            ps.setString(1, u);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1).equals(h) ? null : "Sai mat khau";
        } catch (SQLException e) {
            return "Loi database";
        }
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO users VALUES(?,?)")) {
            ps.setString(1, u);
            ps.setString(2, h);
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Loi database";
        }
    }

    static synchronized void store(String owner, String sender, String raw) {
        String time = now();
        if (db == null) {
            memMails.add(new Mail(memId.incrementAndGet(), owner, sender, raw, time));
            return;
        }
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO mails(owner,sender,raw,time) VALUES(?,?,?,?)")) {
            ps.setString(1, owner);
            ps.setString(2, sender);
            ps.setString(3, raw);
            ps.setString(4, time);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("Lỗi lưu thư: " + e.getMessage());
        }
    }

    static synchronized List<Mail> inbox(String owner) {
        List<Mail> r = new ArrayList<>();
        if (db == null) {
            for (Mail m : memMails) if (m.owner.equals(owner)) r.add(m);
            return r;
        }
        try (PreparedStatement ps = db.prepareStatement("SELECT id,owner,sender,raw,time FROM mails WHERE owner=? ORDER BY id")) {
            ps.setString(1, owner);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) r.add(new Mail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        } catch (SQLException e) {
            log("Lỗi đọc thư: " + e.getMessage());
        }
        return r;
    }

    static synchronized void delete(long id) {
        if (db == null) {
            memMails.removeIf(m -> m.id == id);
            return;
        }
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM mails WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("Lỗi xóa thư: " + e.getMessage());
        }
    }

    /** Dữ liệu cho giao diện: {tài khoản, số thư} */
    static synchronized List<String[]> userRows() {
        List<String[]> r = new ArrayList<>();
        if (db == null) {
            List<String> names = new ArrayList<>(memUsers.keySet());
            Collections.sort(names);
            for (String n : names) {
                int c = 0;
                for (Mail m : memMails) if (m.owner.equals(n)) c++;
                r.add(new String[]{n + "@" + DOMAIN, String.valueOf(c)});
            }
            return r;
        }
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT u.username, (SELECT COUNT(*) FROM mails m WHERE m.owner=u.username) "
                     + "FROM users u ORDER BY u.username")) {
            while (rs.next()) r.add(new String[]{rs.getString(1) + "@" + DOMAIN, String.valueOf(rs.getInt(2))});
        } catch (SQLException e) {
            log("Lỗi đọc tài khoản: " + e.getMessage());
        }
        return r;
    }

    /** Dữ liệu cho giao diện: {id, hộp thư, người gửi, thời gian, dung lượng} (500 thư mới nhất) */
    static synchronized List<String[]> mailRows() {
        List<String[]> r = new ArrayList<>();
        if (db == null) {
            for (int i = memMails.size() - 1; i >= 0 && r.size() < 500; i--) {
                Mail m = memMails.get(i);
                r.add(new String[]{String.valueOf(m.id), m.owner + "@" + DOMAIN, m.sender, m.time, human(m.size())});
            }
            return r;
        }
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,owner,sender,time,length(raw) FROM mails ORDER BY id DESC LIMIT 500")) {
            while (rs.next())
                r.add(new String[]{String.valueOf(rs.getLong(1)), rs.getString(2) + "@" + DOMAIN, rs.getString(3),
                        rs.getString(4), human(rs.getLong(5))});
        } catch (SQLException e) {
            log("Lỗi đọc thư: " + e.getMessage());
        }
        return r;
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    // ------------------------------------------------------------------ HELPERS
    static void log(String s) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + s;
        System.out.println(line);
        logBuf.add(line);
        if (logBuf.size() > 2000) logBuf.remove(0);
        Consumer<String> u = uiLog;
        if (u != null) u.accept(line);
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }

    static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    static BufferedWriter writer(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    static void wr(BufferedWriter w, String s) throws IOException {
        w.write(s);
        w.write("\r\n");
    }

    static void out(BufferedWriter w, String s) throws IOException {
        wr(w, s);
        w.flush();
    }

    /** "<a@b.c>" hoặc "a@b.c" -> a@b.c */
    static String addr(String s) {
        s = s.trim();
        int i = s.indexOf('<'), j = s.indexOf('>');
        if (i >= 0 && j > i) s = s.substring(i + 1, j);
        return s.trim();
    }

    static boolean isLocal(String a) {
        String l = a.toLowerCase();
        return l.matches("[^@\\s]+@(localhost|127\\.0\\.0\\.1)");
    }

    // ------------------------------------------------------------------ SMTP
    static void smtp(Socket s) throws IOException {
        BufferedReader r = reader(s);
        BufferedWriter w = writer(s);
        out(w, "220 " + DOMAIN + " SMTP ready");
        String from = null, line;
        List<String> rcpt = new ArrayList<>();
        while ((line = r.readLine()) != null) {
            String u = line.toUpperCase();
            if (u.startsWith("EHLO") || u.startsWith("HELO")) {
                out(w, "250 " + DOMAIN);
            } else if (u.startsWith("MAIL FROM:")) {
                from = addr(line.substring(10));
                rcpt.clear();
                out(w, "250 OK");
            } else if (u.startsWith("RCPT TO:")) {
                String a = addr(line.substring(8));
                if (from == null) out(w, "503 Can MAIL FROM truoc");
                else if (!isLocal(a)) out(w, "550 Server nay chi nhan thu @" + DOMAIN);
                else {
                    rcpt.add(a.substring(0, a.indexOf('@')).toLowerCase());
                    out(w, "250 OK");
                }
            } else if (u.equals("DATA")) {
                if (from == null || rcpt.isEmpty()) {
                    out(w, "503 Chua co nguoi nhan");
                    continue;
                }
                out(w, "354 Ket thuc bang dong chi co dau cham");
                StringBuilder sb = new StringBuilder();
                String l;
                boolean tooBig = false;
                while ((l = r.readLine()) != null && !l.equals(".")) {
                    if (l.startsWith("..")) l = l.substring(1); // gỡ dot-stuffing
                    if (sb.length() < MAX_MAIL) sb.append(l).append("\r\n");
                    else tooBig = true;
                }
                if (tooBig) {
                    out(w, "552 Thu qua lon (toi da 5MB)");
                } else {
                    String raw = "Received: from client by " + DOMAIN + "; " + now() + "\r\n" + sb;
                    for (String o : new LinkedHashSet<>(rcpt)) store(o, from, raw);
                    log("SMTP: " + from + " -> " + rcpt + " (" + raw.length() / 1024 + " KB)");
                    out(w, "250 OK da luu thu");
                }
                from = null;
                rcpt.clear();
            } else if (u.equals("RSET")) {
                from = null;
                rcpt.clear();
                out(w, "250 OK");
            } else if (u.equals("NOOP")) {
                out(w, "250 OK");
            } else if (u.equals("QUIT")) {
                out(w, "221 Bye");
                return;
            } else {
                out(w, "502 Lenh khong ho tro");
            }
        }
    }

    // ------------------------------------------------------------------ POP3
    static int idx(String arg, int n) {
        try {
            int i = Integer.parseInt(arg.trim().split("\\s+")[0]) - 1;
            return i >= 0 && i < n ? i : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static void pop3(Socket s) throws IOException {
        BufferedReader r = reader(s);
        BufferedWriter w = writer(s);
        out(w, "+OK POP3 server ready");
        String user = null, line;
        List<Mail> box = null;
        boolean[] del = null;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            String cmd = line.split("\\s+")[0].toUpperCase();
            String arg = line.length() > cmd.length() ? line.substring(cmd.length()).trim() : "";
            boolean needAuth = !(cmd.equals("USER") || cmd.equals("PASS") || cmd.equals("QUIT") || cmd.equals("NOOP") || cmd.equals("CAPA"));
            if (needAuth && box == null) {
                out(w, "-ERR Chua dang nhap");
                continue;
            }
            switch (cmd) {
                case "CAPA":
                    out(w, "+OK\r\nUSER\r\nTOP\r\nUIDL\r\n.");
                    break;
                case "USER":
                    user = arg.toLowerCase();
                    out(w, "+OK");
                    break;
                case "PASS": {
                    if (user == null || user.isEmpty()) {
                        out(w, "-ERR Gui USER truoc");
                        break;
                    }
                    String err = auth(user, arg);
                    if (err != null) {
                        out(w, "-ERR " + err);
                        break;
                    }
                    box = inbox(user);
                    del = new boolean[box.size()];
                    log("POP3: " + user + " đăng nhập (" + box.size() + " thư)");
                    out(w, "+OK " + box.size() + " messages");
                    break;
                }
                case "STAT": {
                    int c = 0, sz = 0;
                    for (int i = 0; i < box.size(); i++) if (!del[i]) {
                        c++;
                        sz += box.get(i).size();
                    }
                    out(w, "+OK " + c + " " + sz);
                    break;
                }
                case "LIST":
                case "UIDL": {
                    boolean uidl = cmd.equals("UIDL");
                    if (!arg.isEmpty()) {
                        int i = idx(arg, box.size());
                        if (i < 0 || del[i]) out(w, "-ERR Khong co thu nay");
                        else out(w, "+OK " + (i + 1) + " " + (uidl ? box.get(i).id : box.get(i).size()));
                    } else {
                        wr(w, "+OK");
                        for (int i = 0; i < box.size(); i++)
                            if (!del[i]) wr(w, (i + 1) + " " + (uidl ? box.get(i).id : box.get(i).size()));
                        out(w, ".");
                    }
                    break;
                }
                case "RETR":
                case "TOP": {
                    int i = idx(arg, box.size());
                    if (i < 0 || del[i]) {
                        out(w, "-ERR Khong co thu nay");
                        break;
                    }
                    int top = -1;
                    if (cmd.equals("TOP")) {
                        String[] t = arg.split("\\s+");
                        top = t.length > 1 ? Integer.parseInt(t[1]) : 0;
                    }
                    Mail m = box.get(i);
                    wr(w, "+OK " + m.size() + " octets");
                    boolean inBody = false;
                    int bodyLines = 0;
                    for (String l : m.raw.split("\r?\n")) {
                        if (top >= 0) {
                            if (inBody && bodyLines++ >= top) break;
                        }
                        if (l.isEmpty()) inBody = true;
                        wr(w, l.startsWith(".") ? "." + l : l); // dot-stuffing
                    }
                    out(w, ".");
                    break;
                }
                case "DELE": {
                    int i = idx(arg, box.size());
                    if (i < 0 || del[i]) out(w, "-ERR Khong co thu nay");
                    else {
                        del[i] = true;
                        out(w, "+OK da danh dau xoa");
                    }
                    break;
                }
                case "RSET":
                    Arrays.fill(del, false);
                    out(w, "+OK");
                    break;
                case "NOOP":
                    out(w, "+OK");
                    break;
                case "QUIT":
                    if (box != null) {
                        for (int i = 0; i < box.size(); i++) if (del[i]) delete(box.get(i).id);
                        log("POP3: " + user + " thoát");
                    }
                    out(w, "+OK Bye");
                    return;
                default:
                    out(w, "-ERR Lenh khong ho tro");
            }
        }
    }

    // =====================================================================
    //  GIAO DIỆN THEO DÕI (Swing)
    // =====================================================================
    static final Color ACCENT = new Color(0x5B6CFF), ACCENT_DARK = new Color(0x4857E0);
    static final Color BG = new Color(0xF3F5F9), LINE = new Color(0xE3E7EF);
    static final Color TEXT = new Color(0x1F2430), MUTED = new Color(0x8A93A6);
    static final Color SIDE = new Color(0x151A2B), SIDE_SEL = new Color(0x252C48), SIDE_FIELD = new Color(0x232A42);
    static final Color SIDE_MUTED = new Color(0x7F8AA8), GREEN = new Color(0x2ECC71);

    static Font f(int style, int size) {
        return new Font("Dialog", style, size);
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static class Card extends JPanel {
        final Color fill, line;
        final int arc;

        Card(Color fill, Color line, int arc) {
            this.fill = fill;
            this.line = line;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (line != null) {
                g.setColor(line);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g.dispose();
        }
    }

    static class DarkBtn extends JButton {
        DarkBtn(String t) {
            super(t);
            setFont(f(Font.BOLD, 13));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 32, 34);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(getModel().isRollover() || getModel().isArmed() ? SIDE_SEL : SIDE_FIELD);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g.setColor(Color.WHITE);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Tab dạng viên thuốc. */
    static class Tab extends JToggleButton {
        Tab(String t) {
            super(t);
            setFont(f(Font.BOLD, 13));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 36, 36);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean sel = isSelected();
            if (sel || getModel().isRollover()) {
                g.setColor(sel ? ACCENT : new Color(0xE6E9F2));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            }
            g.setColor(sel ? Color.WHITE : MUTED);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    static class DotIcon implements Icon {
        final Color c;

        DotIcon(Color c) { this.c = c; }

        public int getIconWidth() { return 12; }

        public int getIconHeight() { return 12; }

        public void paintIcon(Component cmp, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(c);
            g.fillOval(x + 1, y + 1, 10, 10);
            g.dispose();
        }
    }

    static class ThinScroll extends BasicScrollBarUI {
        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override protected JButton createDecreaseButton(int o) { return zero(); }

        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }

        @Override
        protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(isThumbRollover() ? new Color(0xA9B1C9) : new Color(0xC9CFDF));
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g.dispose();
        }
    }

    static void thin(JScrollPane sp, Color bg) {
        JScrollBar v = sp.getVerticalScrollBar(), h = sp.getHorizontalScrollBar();
        v.setUI(new ThinScroll());
        h.setUI(new ThinScroll());
        v.setPreferredSize(new Dimension(10, 0));
        h.setPreferredSize(new Dimension(0, 10));
        v.setBackground(bg);
        h.setBackground(bg);
        v.setUnitIncrement(16);
    }

    static class CellR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean fo, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, fo, r, c);
            String col = t.getColumnName(c);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 10));
            setFont(f(Font.PLAIN, 13));
            setForeground(TEXT);
            if (col.equals("Tài khoản") || col.equals("Hộp thư")) {
                setFont(f(Font.BOLD, 13));
            } else if (col.equals("Thời gian") || col.equals("ID") || col.equals("Dung lượng")) {
                setForeground(MUTED);
                setFont(f(Font.PLAIN, 12));
            }
            return this;
        }
    }

    static JScrollPane styled(JTable t) {
        t.setRowHeight(34);
        t.setShowGrid(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(new Color(0xF0F2F7));
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setBackground(Color.WHITE);
        t.setSelectionBackground(new Color(0xE8EBFF));
        t.setSelectionForeground(TEXT);
        t.setFillsViewportHeight(true);
        t.setDefaultRenderer(Object.class, new CellR());
        JTableHeader th = t.getTableHeader();
        th.setReorderingAllowed(false);
        th.setPreferredSize(new Dimension(0, 34));
        th.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean fo, int r, int c) {
                super.getTableCellRendererComponent(tb, v, s, fo, r, c);
                setBackground(Color.WHITE);
                setForeground(MUTED);
                setFont(f(Font.BOLD, 11));
                setText(String.valueOf(v).toUpperCase());
                setHorizontalAlignment(SwingConstants.LEFT);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                        BorderFactory.createEmptyBorder(0, 14, 0, 10)));
                return this;
            }
        });
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createLineBorder(LINE));
        sp.getViewport().setBackground(Color.WHITE);
        thin(sp, Color.WHITE);
        return sp;
    }

    static JComponent badge(String text) {
        JLabel l = new JLabel(text);
        l.setFont(f(Font.BOLD, 12));
        l.setForeground(new Color(0xC9D0E6));
        Card c = new Card(SIDE_FIELD, null, 30);
        c.setLayout(new FlowLayout(FlowLayout.CENTER, 14, 7));
        c.add(l);
        c.setAlignmentY(0.5f);
        c.setMaximumSize(c.getPreferredSize());
        return c;
    }

    static class Gui extends JFrame {
        final JTextArea logArea = new JTextArea();
        final DefaultTableModel userModel = new DefaultTableModel(new String[]{"Tài khoản", "Số thư"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        final DefaultTableModel mailModel = new DefaultTableModel(new String[]{"ID", "Hộp thư", "Người gửi", "Thời gian", "Dung lượng"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        final JLabel countLbl = new JLabel(" ");

        Gui() {
            super("Mail Server");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(980, 640);
            setMinimumSize(new Dimension(820, 520));
            setLocationRelativeTo(null);
            getContentPane().setBackground(BG);
            setLayout(new BorderLayout());

            // --- thanh trên (tối)
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(SIDE);
            bar.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            JLabel brand = new JLabel("Mail Server");
            brand.setFont(f(Font.BOLD, 18));
            brand.setForeground(Color.WHITE);
            brand.setAlignmentY(0.5f);
            left.add(brand);
            left.add(Box.createHorizontalStrut(22));
            for (String b : new String[]{"SMTP " + SMTP_PORT, "POP3 " + POP3_PORT, "@" + DOMAIN,
                    db != null ? "SQLite · mail.db" : "Không database (RAM)"}) {
                left.add(badge(b));
                left.add(Box.createHorizontalStrut(8));
            }
            bar.add(left, BorderLayout.WEST);
            JLabel st = new JLabel("Đang chạy");
            st.setFont(f(Font.BOLD, 12));
            st.setForeground(new Color(0xD8F5E3));
            st.setIconTextGap(8);
            st.setIcon(new DotIcon(GREEN));
            bar.add(st, BorderLayout.EAST);
            add(bar, BorderLayout.NORTH);

            // --- các trang
            CardLayout cl = new CardLayout();
            JPanel pages = new JPanel(cl);
            pages.setOpaque(false);
            pages.add(buildLog(), "log");
            pages.add(page(styled(new JTable(userModel))), "users");
            pages.add(page(styled(new JTable(mailModel))), "mails");

            String[][] tabs = {{"Nhật ký", "log"}, {"Tài khoản", "users"}, {"Thư đã lưu", "mails"}};
            ButtonGroup bg = new ButtonGroup();
            JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            nav.setOpaque(false);
            for (String[] t : tabs) {
                Tab b = new Tab(t[0]);
                bg.add(b);
                nav.add(b);
                b.addActionListener(e -> cl.show(pages, t[1]));
                if (t[1].equals("log")) b.setSelected(true);
            }
            countLbl.setFont(f(Font.PLAIN, 12));
            countLbl.setForeground(MUTED);
            JPanel navRow = new JPanel(new BorderLayout());
            navRow.setOpaque(false);
            navRow.add(nav, BorderLayout.WEST);
            navRow.add(countLbl, BorderLayout.EAST);

            JPanel body = new JPanel(new BorderLayout(0, 12));
            body.setOpaque(false);
            body.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
            body.add(navRow, BorderLayout.NORTH);
            body.add(pages, BorderLayout.CENTER);
            add(body, BorderLayout.CENTER);

            // --- nhật ký trực tiếp
            for (String l : logBuf) logArea.append(l + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            uiLog = line -> SwingUtilities.invokeLater(() -> {
                logArea.append(line + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });

            // --- làm mới bảng định kỳ
            reload();
            new javax.swing.Timer(2000, e -> reload()).start();
        }

        JPanel page(JComponent content) {
            Card c = new Card(Color.WHITE, LINE, 22);
            c.setLayout(new BorderLayout());
            c.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            c.add(content, BorderLayout.CENTER);
            return c;
        }

        JPanel buildLog() {
            logArea.setEditable(false);
            logArea.setBackground(SIDE);
            logArea.setForeground(new Color(0xB8C1DC));
            logArea.setCaretColor(SIDE);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
            JScrollPane sp = new JScrollPane(logArea);
            sp.setBorder(null);
            sp.getViewport().setBackground(SIDE);
            thin(sp, SIDE);
            JButton clear = new DarkBtn("Xóa màn hình");
            clear.addActionListener(e -> logArea.setText(""));
            JLabel title = new JLabel("NHẬT KÝ HOẠT ĐỘNG");
            title.setFont(f(Font.BOLD, 10));
            title.setForeground(SIDE_MUTED);
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            head.add(title, BorderLayout.WEST);
            head.add(clear, BorderLayout.EAST);
            Card c = new Card(SIDE, null, 22);
            c.setLayout(new BorderLayout());
            c.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 10));
            c.add(head, BorderLayout.NORTH);
            c.add(sp, BorderLayout.CENTER);
            return c;
        }

        void reload() {
            userModel.setRowCount(0);
            for (String[] r : userRows()) userModel.addRow(r);
            mailModel.setRowCount(0);
            for (String[] r : mailRows()) mailModel.addRow(r);
            countLbl.setText(userModel.getRowCount() + " tài khoản  ·  " + mailModel.getRowCount() + " thư đang lưu");
        }
    }
}