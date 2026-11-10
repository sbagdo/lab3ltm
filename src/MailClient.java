import javax.net.ssl.SSLSocketFactory;
import javax.swing.*;
import javax.swing.plaf.basic.*;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MAIL CLIENT (Lab 3, Swing) - tự cài giao thức bằng Socket, KHÔNG dùng JavaMail
 *  - Gửi thư : SMTP  (EHLO / AUTH LOGIN / MAIL FROM / RCPT TO / DATA / QUIT)
 *  - Nhận thư: POP3  (USER / PASS / STAT / RETR / DELE / QUIT)
 *  - Hai chế độ (nút ở thanh trên cùng):
 *      + Localhost : MailServer của mình (SMTP 2525, POP3 1100, không SSL, không cần AUTH)
 *      + Gmail     : smtp.gmail.com:465 (SSL) + pop.gmail.com:995 (SSL)
 *                    Cần: bật POP trong Gmail (Cài đặt > Chuyển tiếp và POP/IMAP),
 *                         bật xác minh 2 bước và tạo "Mật khẩu ứng dụng" (App Password) 16 ký tự để dán vào ô mật khẩu.
 *  - Thư tiếng Việt: tiêu đề mã hóa =?UTF-8?B?...?=, nội dung UTF-8 + base64.
 *  - Đọc thư: tự giải mã base64 / quoted-printable / multipart (lấy phần text).
 *  - Giao diện mới: thanh cấu hình tối, danh sách thư có avatar, khung đọc thư dạng thẻ.
 *
 * Biên dịch : javac -encoding UTF-8 MailClient.java
 * Chạy      : java MailClient     (chạy nhiều lần để mở nhiều tài khoản: alice@localhost, bob@localhost...)
 */
public class MailClient extends JFrame {
    static final int FETCH_LIMIT = 30; // chỉ tải tối đa 30 thư mới nhất (Gmail có thể có hàng nghìn thư)

    // =================================================================== BẢNG MÀU + THÀNH PHẦN GIAO DIỆN
    static final Color ACCENT = new Color(0x5B6CFF), ACCENT_DARK = new Color(0x4857E0);
    static final Color BG = new Color(0xF3F5F9), LINE = new Color(0xE3E7EF);
    static final Color TEXT = new Color(0x1F2430), MUTED = new Color(0x8A93A6);
    static final Color SIDE = new Color(0x151A2B), SIDE_SEL = new Color(0x252C48), SIDE_FIELD = new Color(0x232A42);
    static final Color SIDE_MUTED = new Color(0x7F8AA8), RED = new Color(0xE5484D);
    static final int[] AVATAR_COLORS = {0x5B6CFF, 0xEF6C8F, 0x2EAA6B, 0xF2A33A, 0x3FA7D6, 0x9B6BDF, 0xE5604D};

    static Font f(int style, int size) {
        return new Font("Dialog", style, size);
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static void fieldBg(Graphics g0, JComponent c, Color fill) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        g.setColor(fill);
        g.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 18, 18);
        g.dispose();
    }

    static void avatar(Graphics2D g, String n, int x, int y, int d) {
        g.setColor(new Color(AVATAR_COLORS[(n.hashCode() & 0x7fffffff) % AVATAR_COLORS.length]));
        g.fillOval(x, y, d, d);
        g.setColor(Color.WHITE);
        g.setFont(f(Font.BOLD, d / 2));
        FontMetrics fm = g.getFontMetrics();
        String s = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
        g.drawString(s, x + (d - fm.stringWidth(s)) / 2, y + (d - fm.getHeight()) / 2 + fm.getAscent());
    }

    static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n) + "...") > max) n--;
        return s.substring(0, n) + "...";
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

    static class RField extends JTextField {
        final Color fill;

        RField(String text, int cols, Color fill, Color fg) {
            super(text, cols);
            this.fill = fill;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
            setFont(f(Font.PLAIN, 13));
            setForeground(fg);
            setCaretColor(fg);
            setSelectionColor(ACCENT);
            setSelectedTextColor(Color.WHITE);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.height = 34;
            return d;
        }

        @Override
        protected void paintComponent(Graphics g) {
            fieldBg(g, this, fill);
            super.paintComponent(g);
        }
    }

    static class RPass extends JPasswordField {
        final Color fill;

        RPass(int cols, Color fill, Color fg) {
            super(cols);
            this.fill = fill;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
            setFont(f(Font.PLAIN, 13));
            setForeground(fg);
            setCaretColor(fg);
            setSelectionColor(ACCENT);
            setSelectedTextColor(Color.WHITE);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.height = 34;
            return d;
        }

        @Override
        protected void paintComponent(Graphics g) {
            fieldBg(g, this, fill);
            super.paintComponent(g);
        }
    }

    /** Nút phẳng bo tròn. */
    static class Btn extends JButton {
        static final int PRIMARY = 0, GHOST = 1, DARK = 2, DANGER = 3;
        final int kind;

        Btn(String t, int kind) {
            super(t);
            this.kind = kind;
            setFont(f(Font.BOLD, 13));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setMargin(new Insets(0, 0, 0, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 32, 36);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean hov = getModel().isRollover() || getModel().isArmed();
            Color bg, fg;
            switch (kind) {
                case PRIMARY:
                    bg = hov ? ACCENT_DARK : ACCENT;
                    fg = Color.WHITE;
                    break;
                case DARK:
                    bg = hov ? SIDE_SEL : SIDE_FIELD;
                    fg = Color.WHITE;
                    break;
                case DANGER:
                    bg = hov ? new Color(0xFDE8E9) : Color.WHITE;
                    fg = RED;
                    break;
                default:
                    bg = hov ? new Color(0xE6E9F2) : Color.WHITE;
                    fg = TEXT;
            }
            if (!isEnabled()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
            g.setColor(bg);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            if (kind == GHOST || kind == DANGER) {
                g.setColor(LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            }
            g.setColor(fg);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String t = getText();
            g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Nút bật/tắt dạng viên thuốc (trên nền tối). */
    static class Pill extends JToggleButton {
        final int h;

        Pill(String t, int h, int fs) {
            super(t);
            this.h = h;
            setFont(f(Font.BOLD, fs));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + (h >= 34 ? 34 : 22), h);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean sel = isSelected();
            g.setColor(sel ? ACCENT : getModel().isRollover() ? SIDE_SEL : SIDE_FIELD);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setColor(sel ? Color.WHITE : new Color(0xA9B1C9));
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String t = getText();
            g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
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

    static JSplitPane split(int orient, Component a, Component b) {
        JSplitPane sp = new JSplitPane(orient, a, b);
        sp.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(BG);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        sp.setBorder(null);
        sp.setDividerSize(12);
        sp.setContinuousLayout(true);
        sp.setOpaque(false);
        return sp;
    }

    static <T extends JComponent> T mid(T c) {
        c.setAlignmentY(0.5f);
        c.setMaximumSize(c.getPreferredSize());
        return c;
    }

    static void addTo(JPanel p, JComponent c, int gap) {
        p.add(mid(c));
        if (gap > 0) p.add(Box.createHorizontalStrut(gap));
    }

    static JPanel boxRow() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(0f);
        return p;
    }

    static JLabel barLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(f(Font.PLAIN, 12));
        l.setForeground(SIDE_MUTED);
        return l;
    }

    static JLabel smallCaps(String s) {
        JLabel l = new JLabel(s);
        l.setFont(f(Font.BOLD, 10));
        l.setForeground(MUTED);
        return l;
    }

    static String senderName(String from) {
        String s = from == null ? "?" : from.trim();
        int i = s.indexOf('<');
        if (i > 0) {
            String n = s.substring(0, i).trim().replace("\"", "");
            if (!n.isEmpty()) return n;
        }
        if (i >= 0) {
            int j = s.indexOf('>', i);
            if (j > i) return s.substring(i + 1, j);
        }
        return s;
    }

    static String shortDate(Msg m) {
        if (m.ts > 0) return new SimpleDateFormat("dd/MM HH:mm").format(new Date(m.ts));
        return m.date == null ? "" : m.date;
    }

    /** Một dòng thư trong danh sách. */
    static class MailCell extends JPanel implements ListCellRenderer<Msg> {
        Msg m;
        boolean sel;

        MailCell() {
            setOpaque(false);
        }

        public Component getListCellRendererComponent(JList<? extends Msg> l, Msg v, int i, boolean s, boolean fo) {
            m = v;
            sel = s;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (m == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            if (sel) {
                g.setColor(new Color(0xE8EBFF));
                g.fillRoundRect(8, 3, w - 16, h - 6, 16, 16);
                g.setColor(ACCENT);
                g.fillRoundRect(8, 16, 3, h - 32, 3, 3);
            }
            String who = senderName(m.from);
            avatar(g, who, 22, (h - 42) / 2, 42);
            int x = 76, right = w - 20;
            g.setFont(f(Font.PLAIN, 11));
            g.setColor(MUTED);
            String date = shortDate(m);
            int dw = g.getFontMetrics().stringWidth(date);
            g.drawString(date, right - dw, 28);
            g.setFont(f(Font.BOLD, 14));
            g.setColor(TEXT);
            g.drawString(ellipsize(who, g.getFontMetrics(), right - dw - 10 - x), x, 28);
            g.setFont(f(Font.PLAIN, 13));
            g.setColor(TEXT);
            g.drawString(ellipsize(m.subject, g.getFontMetrics(), right - x), x, 48);
            g.setFont(f(Font.PLAIN, 12));
            g.setColor(MUTED);
            String pv = m.body == null ? "" : m.body.replaceAll("\\s+", " ").trim();
            g.drawString(ellipsize(pv, g.getFontMetrics(), right - x), x, 66);
            g.dispose();
        }
    }

    static class AvatarBox extends JComponent {
        String name = "";

        AvatarBox() {
            setPreferredSize(new Dimension(46, 46));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            avatar(g, name, 0, 0, 46);
            g.dispose();
        }
    }

    // =================================================================== UI
    JToggleButton presetLocal = new Pill("Localhost", 34, 13), presetGmail = new Pill("Gmail", 34, 13);
    JTextField user = new RField("", 16, SIDE_FIELD, Color.WHITE), smtpHost = new RField("", 13, SIDE_FIELD, Color.WHITE),
            smtpPort = new RField("", 4, SIDE_FIELD, Color.WHITE), popHost = new RField("", 13, SIDE_FIELD, Color.WHITE),
            popPort = new RField("", 4, SIDE_FIELD, Color.WHITE);
    JPasswordField pass = new RPass(12, SIDE_FIELD, Color.WHITE);
    JToggleButton smtpSsl = new Pill("SSL", 28, 11), smtpAuth = new Pill("AUTH", 28, 11), popSsl = new Pill("SSL", 28, 11);
    DefaultListModel<Msg> listModel = new DefaultListModel<>();
    JList<Msg> list = new JList<>(listModel);
    JTextArea view = new JTextArea();
    JTextArea subjectArea = new JTextArea();
    JLabel fromLbl = new JLabel(), dateLbl = new JLabel(), countLbl = new JLabel("0 thư");
    AvatarBox avatarBox = new AvatarBox();
    CardLayout readerCl = new CardLayout();
    JPanel reader = new JPanel(readerCl);
    JLabel status = new JLabel("Sẵn sàng.");
    final List<Msg> msgs = new ArrayList<>();

    static class Msg {
        int num;
        long ts;
        String from, subject, date, body;
    }

    MailClient() {
        setTitle("Mail Client (SMTP + POP3)");
        setSize(1120, 700);
        setMinimumSize(new Dimension(1000, 600));
        setLocationByPlatform(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        // --- thanh cấu hình (tối)
        ButtonGroup pg = new ButtonGroup();
        pg.add(presetLocal);
        pg.add(presetGmail);
        presetLocal.setSelected(true);

        JLabel brand = new JLabel("Mail Client");
        brand.setFont(f(Font.BOLD, 18));
        brand.setForeground(Color.WHITE);
        JButton advBtn = new Btn("Cài đặt máy chủ", Btn.DARK);

        JPanel row1 = boxRow();
        addTo(row1, brand, 22);
        addTo(row1, presetLocal, 6);
        addTo(row1, presetGmail, 20);
        addTo(row1, barLabel("Email"), 6);
        addTo(row1, user, 12);
        addTo(row1, barLabel("Mật khẩu"), 6);
        addTo(row1, pass, 0);
        row1.add(Box.createHorizontalGlue());
        addTo(row1, advBtn, 0);

        JPanel adv = boxRow();
        adv.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        addTo(adv, barLabel("SMTP"), 6);
        addTo(adv, smtpHost, 6);
        addTo(adv, smtpPort, 6);
        addTo(adv, smtpSsl, 4);
        addTo(adv, smtpAuth, 28);
        addTo(adv, barLabel("POP3"), 6);
        addTo(adv, popHost, 6);
        addTo(adv, popPort, 6);
        addTo(adv, popSsl, 0);
        adv.setVisible(false);
        advBtn.addActionListener(e -> {
            adv.setVisible(!adv.isVisible());
            revalidate();
            repaint();
        });

        JPanel bar = new JPanel();
        bar.setBackground(SIDE);
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        bar.add(row1);
        bar.add(adv);
        add(bar, BorderLayout.NORTH);
        presetLocal.addActionListener(e -> applyPreset());
        presetGmail.addActionListener(e -> applyPreset());
        applyPreset();

        // --- thanh nút chức năng
        JButton bNew = new Btn("Soạn thư (SMTP)", Btn.PRIMARY);
        JButton bGet = new Btn("Nhận thư (POP3)", Btn.GHOST);
        JButton bDel = new Btn("Xóa thư", Btn.DANGER);
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tb.setOpaque(false);
        tb.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        tb.add(bNew);
        tb.add(bGet);
        tb.add(bDel);
        bNew.addActionListener(e -> compose());
        bGet.addActionListener(e -> receive());
        bDel.addActionListener(e -> deleteSelected());

        // --- trái: danh sách thư
        list.setCellRenderer(new MailCell());
        list.setFixedCellHeight(78);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        JScrollPane ls = new JScrollPane(list);
        ls.setBorder(null);
        ls.getViewport().setBackground(Color.WHITE);
        thin(ls, Color.WHITE);
        JLabel inboxTitle = new JLabel("Hộp thư đến");
        inboxTitle.setFont(f(Font.BOLD, 16));
        inboxTitle.setForeground(TEXT);
        countLbl.setFont(f(Font.PLAIN, 12));
        countLbl.setForeground(MUTED);
        JPanel lh = new JPanel(new BorderLayout());
        lh.setOpaque(false);
        lh.setBorder(BorderFactory.createEmptyBorder(0, 14, 6, 14));
        lh.add(inboxTitle, BorderLayout.WEST);
        lh.add(countLbl, BorderLayout.EAST);
        Card leftCard = new Card(Color.WHITE, LINE, 22);
        leftCard.setLayout(new BorderLayout(0, 6));
        leftCard.setBorder(BorderFactory.createEmptyBorder(16, 6, 10, 6));
        leftCard.add(lh, BorderLayout.NORTH);
        leftCard.add(ls, BorderLayout.CENTER);

        // --- phải: khung đọc thư
        buildReader();

        JSplitPane sp = split(JSplitPane.HORIZONTAL_SPLIT, leftCard, reader);
        sp.setDividerLocation(410);
        JPanel sw = new JPanel(new BorderLayout());
        sw.setOpaque(false);
        sw.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        sw.add(sp, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(tb, BorderLayout.NORTH);
        body.add(sw, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        // --- thanh trạng thái
        status.setFont(f(Font.PLAIN, 12));
        status.setForeground(TEXT);
        JPanel st = new JPanel(new BorderLayout());
        st.setBackground(Color.WHITE);
        st.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(9, 18, 9, 18)));
        st.add(status, BorderLayout.CENTER);
        add(st, BorderLayout.SOUTH);
    }

    void buildReader() {
        subjectArea.setEditable(false);
        subjectArea.setLineWrap(true);
        subjectArea.setWrapStyleWord(true);
        subjectArea.setOpaque(false);
        subjectArea.setBorder(null);
        subjectArea.setFont(f(Font.BOLD, 20));
        subjectArea.setForeground(TEXT);
        fromLbl.setFont(f(Font.BOLD, 14));
        fromLbl.setForeground(TEXT);
        dateLbl.setFont(f(Font.PLAIN, 12));
        dateLbl.setForeground(MUTED);
        JPanel names = new JPanel(new GridLayout(0, 1, 0, 2));
        names.setOpaque(false);
        names.add(fromLbl);
        names.add(dateLbl);
        JPanel meta = new JPanel(new BorderLayout(12, 0));
        meta.setOpaque(false);
        meta.add(avatarBox, BorderLayout.WEST);
        meta.add(names, BorderLayout.CENTER);
        JPanel head = new JPanel(new BorderLayout(0, 14));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                BorderFactory.createEmptyBorder(0, 0, 16, 0)));
        head.add(subjectArea, BorderLayout.NORTH);
        head.add(meta, BorderLayout.CENTER);

        view.setEditable(false);
        view.setLineWrap(true);
        view.setWrapStyleWord(true);
        view.setFont(f(Font.PLAIN, 15));
        view.setForeground(TEXT);
        view.setBackground(Color.WHITE);
        view.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 8));
        JScrollPane bs = new JScrollPane(view);
        bs.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        bs.getViewport().setBackground(Color.WHITE);
        thin(bs, Color.WHITE);

        Card mail = new Card(Color.WHITE, LINE, 22);
        mail.setLayout(new BorderLayout());
        mail.setBorder(BorderFactory.createEmptyBorder(22, 26, 16, 18));
        mail.add(head, BorderLayout.NORTH);
        mail.add(bs, BorderLayout.CENTER);

        JLabel e1 = new JLabel("Chọn một thư để đọc", SwingConstants.CENTER);
        e1.setFont(f(Font.BOLD, 16));
        e1.setForeground(TEXT);
        JLabel e2 = new JLabel("hoặc bấm \"Nhận thư\" để tải thư mới", SwingConstants.CENTER);
        e2.setFont(f(Font.PLAIN, 13));
        e2.setForeground(MUTED);
        JPanel en = new JPanel(new GridLayout(0, 1, 0, 4));
        en.setOpaque(false);
        en.add(e1);
        en.add(e2);
        Card empty = new Card(Color.WHITE, LINE, 22);
        empty.setLayout(new GridBagLayout());
        empty.add(en);

        reader.setOpaque(false);
        reader.add(empty, "empty");
        reader.add(mail, "mail");
        readerCl.show(reader, "empty");
    }

    void applyPreset() {
        boolean local = presetLocal.isSelected();
        smtpHost.setText(local ? "localhost" : "smtp.gmail.com");
        smtpPort.setText(local ? "2525" : "465");
        smtpSsl.setSelected(!local);
        smtpAuth.setSelected(!local);
        popHost.setText(local ? "localhost" : "pop.gmail.com");
        popPort.setText(local ? "1100" : "995");
        popSsl.setSelected(!local);
    }

    // =================================================================== SOCKET GIAO THỨC DÒNG
    static class Conn implements Closeable {
        final Socket s;
        final BufferedReader r;
        final BufferedWriter w;

        Conn(String host, int port, boolean ssl) throws IOException {
            if (ssl) s = SSLSocketFactory.getDefault().createSocket(host, port);
            else {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 8000);
            }
            s.setSoTimeout(20000);
            r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(String l) throws IOException {
            w.write(l + "\r\n");
            w.flush();
        }

        String line() throws IOException {
            String l = r.readLine();
            if (l == null) throw new IOException("Server đóng kết nối");
            return l;
        }

        @Override
        public void close() {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Đọc 1 phản hồi SMTP (có thể nhiều dòng "250-..."), trả về dòng cuối. */
    static String smtpReply(Conn c) throws IOException {
        String l;
        do {
            l = c.line();
        } while (l.length() > 3 && l.charAt(3) == '-');
        return l;
    }

    static void expect(Conn c, String code) throws IOException {
        String l = smtpReply(c);
        if (!l.startsWith(code)) throw new IOException("SMTP: " + l);
    }

    static String popOk(Conn c) throws IOException {
        String l = c.line();
        if (!l.startsWith("+OK")) throw new IOException("POP3: " + l);
        return l;
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    String fromAddr() {
        String u = user.getText().trim();
        return u.contains("@") ? u : u + "@localhost";
    }

    // =================================================================== GỬI (SMTP)
    void smtpSend(String to, String subject, String body) throws IOException {
        String from = fromAddr();
        try (Conn c = new Conn(smtpHost.getText().trim(), Integer.parseInt(smtpPort.getText().trim()), smtpSsl.isSelected())) {
            expect(c, "220");
            c.send("EHLO client");
            expect(c, "250");
            if (smtpAuth.isSelected()) {
                c.send("AUTH LOGIN");
                expect(c, "334");
                c.send(b64(user.getText().trim()));
                expect(c, "334");
                c.send(b64(new String(pass.getPassword())));
                expect(c, "235");
            }
            c.send("MAIL FROM:<" + from + ">");
            expect(c, "250");
            List<String> rcpts = new ArrayList<>();
            for (String t : to.split("[,;]")) if (!t.trim().isEmpty()) rcpts.add(t.trim());
            for (String t : rcpts) {
                c.send("RCPT TO:<" + t + ">");
                expect(c, "250");
            }
            c.send("DATA");
            expect(c, "354");
            StringBuilder m = new StringBuilder();
            m.append("From: <").append(from).append(">\r\n");
            m.append("To: ").append(String.join(", ", rcpts)).append("\r\n");
            m.append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n");
            m.append("Date: ").append(new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date())).append("\r\n");
            m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
            m.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
            c.w.write(m.toString());
            c.send(".");
            expect(c, "250");
            c.send("QUIT");
        }
    }

    void compose() {
        JTextField to = new RField("", 34, BG, TEXT), subj = new RField("", 34, BG, TEXT);
        JTextArea body = new JTextArea(10, 34);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setFont(f(Font.PLAIN, 14));
        body.setForeground(TEXT);
        body.setBackground(BG);
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane bsp = new JScrollPane(body);
        bsp.setBorder(BorderFactory.createLineBorder(LINE));
        thin(bsp, BG);

        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        JLabel head = new JLabel("Soạn thư");
        head.setFont(f(Font.BOLD, 20));
        head.setForeground(ACCENT);
        g.gridy = 0;
        g.insets = new Insets(0, 0, 14, 0);
        p.add(head, g);
        Object[][] rows = {
                {"ĐẾN (nhiều người cách nhau dấu phẩy)", to},
                {"TIÊU ĐỀ", subj}};
        int y = 1;
        for (Object[] r : rows) {
            g.gridy = y++;
            g.insets = new Insets(0, 0, 3, 0);
            p.add(smallCaps((String) r[0]), g);
            g.gridy = y++;
            g.insets = new Insets(0, 0, 10, 0);
            p.add((JComponent) r[1], g);
        }
        g.gridy = y++;
        g.insets = new Insets(0, 0, 3, 0);
        p.add(smallCaps("NỘI DUNG"), g);
        g.gridy = y;
        g.insets = new Insets(0, 0, 0, 0);
        p.add(bsp, g);

        if (JOptionPane.showConfirmDialog(this, p, "Soạn thư", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        status("Đang gửi qua SMTP...");
        bg(() -> {
            try {
                smtpSend(to.getText(), subj.getText(), body.getText());
                status("Đã gửi thư tới " + to.getText());
            } catch (Exception e) {
                status("Gửi thất bại: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Gửi thất bại: " + e.getMessage()));
            }
        });
    }

    // =================================================================== NHẬN (POP3)
    Conn popLogin() throws IOException {
        Conn c = new Conn(popHost.getText().trim(), Integer.parseInt(popPort.getText().trim()), popSsl.isSelected());
        try {
            popOk(c);
            c.send("USER " + (popHost.getText().contains("gmail") ? "recent:" : "") + user.getText().trim());
            popOk(c);
            c.send("PASS " + new String(pass.getPassword()));
            popOk(c);
            return c;
        } catch (IOException e) {
            c.close();
            throw e;
        }
    }

    void receive() {
        status("Đang nhận thư qua POP3...");
        bg(() -> {
            List<Msg> got = new ArrayList<>();
            try (Conn c = popLogin()) {
                c.send("STAT");
                int n = Integer.parseInt(popOk(c).split("\\s+")[1]);
                for (int i = n; i >= 1 && i > n - FETCH_LIMIT; i--) {
                    c.send("RETR " + i);
                    popOk(c);
                    StringBuilder raw = new StringBuilder();
                    String l;
                    while (!(l = c.line()).equals(".")) {
                        if (l.startsWith("..")) l = l.substring(1);
                        raw.append(l).append("\n");
                    }
                    Msg m = parse(raw.toString());
                    m.num = i;
                    got.add(m);
                }
                got.sort((a, b) -> Long.compare(b.ts, a.ts)); // mới nhất lên đầu
                c.send("QUIT");
                status("Có " + n + " thư trên server, đã tải " + got.size() + " thư mới nhất.");
            } catch (Exception e) {
                status("Nhận thư thất bại: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Nhận thư thất bại: " + e.getMessage()));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                msgs.clear();
                msgs.addAll(got);
                listModel.clear();
                for (Msg m : msgs) listModel.addElement(m);
                countLbl.setText(msgs.size() + " thư");
                readerCl.show(reader, "empty");
            });
        });
    }

    void deleteSelected() {
        Msg m = list.getSelectedValue();
        if (m == null) return;
        if (JOptionPane.showConfirmDialog(this, "Xóa thư \"" + m.subject + "\" trên server?", "Xóa", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            return;
        bg(() -> {
            try (Conn c = popLogin()) {
                c.send("DELE " + m.num);
                popOk(c);
                c.send("QUIT");
                popOk(c);
                status("Đã xóa thư #" + m.num);
            } catch (Exception e) {
                status("Xóa thất bại: " + e.getMessage());
                return;
            }
            receive();
        });
    }

    void showSelected() {
        Msg m = list.getSelectedValue();
        if (m == null) {
            readerCl.show(reader, "empty");
            return;
        }
        subjectArea.setText(m.subject);
        fromLbl.setText(m.from);
        dateLbl.setText(m.date);
        avatarBox.name = senderName(m.from);
        avatarBox.repaint();
        view.setText(m.body);
        view.setCaretPosition(0);
        readerCl.show(reader, "mail");
    }

    // =================================================================== PHÂN TÍCH THƯ (MIME)
    static final Pattern BLANK = Pattern.compile("\r?\n\r?\n");

    static String[] split(String raw) {
        Matcher m = BLANK.matcher(raw);
        if (m.find()) return new String[]{raw.substring(0, m.start()), raw.substring(m.end())};
        return new String[]{raw, ""};
    }

    static Map<String, String> headers(String head) {
        Map<String, String> m = new HashMap<>();
        String last = null;
        for (String l : head.split("\r?\n")) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && last != null) m.put(last, m.get(last) + " " + l.trim());
            else {
                int i = l.indexOf(':');
                if (i > 0) {
                    last = l.substring(0, i).toLowerCase();
                    m.put(last, l.substring(i + 1).trim());
                }
            }
        }
        return m;
    }

    static Msg parse(String raw) {
        String[] hb = split(raw);
        Map<String, String> h = headers(hb[0]);
        Msg m = new Msg();
        m.from = dec(h.getOrDefault("from", "?"));
        m.subject = dec(h.getOrDefault("subject", "(không có tiêu đề)"));
        m.date = h.getOrDefault("date", "");
        m.ts = dateMillis(m.date);
        String t = textOf(h, hb[1]);
        m.body = t == null ? "(thư không có phần văn bản)" : t;
        return m;
    }

    static long dateMillis(String d) {
        try {
            d = d.replaceAll("\\(.*?\\)", "").trim(); // bỏ phần chú thích như (UTC)
            return ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static String textOfRaw(String raw) {
        String[] hb = split(raw);
        return textOf(headers(hb[0]), hb[1]);
    }

    static String textOf(Map<String, String> h, String body) {
        String ct = h.getOrDefault("content-type", "text/plain; charset=UTF-8"), lc = ct.toLowerCase();
        if (lc.startsWith("multipart/")) {
            Matcher m = Pattern.compile("boundary=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(ct);
            if (!m.find()) return null;
            for (String part : body.split(Pattern.quote("--" + m.group(1)))) {
                part = part.replaceFirst("^\\r?\\n", "");
                if (part.trim().isEmpty() || part.startsWith("--")) continue;
                String t = textOfRaw(part);
                if (t != null) return t;
            }
            return null;
        }
        if (!lc.startsWith("text/") || h.getOrDefault("content-disposition", "").toLowerCase().startsWith("attachment"))
            return null;
        Charset cs = StandardCharsets.UTF_8;
        Matcher cm = Pattern.compile("charset=\"?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
        try {
            if (cm.find()) cs = Charset.forName(cm.group(1));
        } catch (Exception ignored) {
        }
        String enc = h.getOrDefault("content-transfer-encoding", "7bit").trim().toLowerCase(), txt = body;
        try {
            if (enc.equals("base64")) txt = new String(Base64.getMimeDecoder().decode(body.trim()), cs);
            else if (enc.equals("quoted-printable")) txt = new String(qp(body), cs);
        } catch (IllegalArgumentException ignored) {
        }
        if (lc.startsWith("text/html"))
            txt = txt.replaceAll("(?is)<(script|style).*?</\\1>", "").replaceAll("(?s)<[^>]+>", "")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
        return txt.trim();
    }

    static byte[] qp(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 1 < s.length() && (s.charAt(i + 1) == '\n' || s.charAt(i + 1) == '\r')) { // soft line break
                if (s.charAt(i + 1) == '\r' && i + 2 < s.length() && s.charAt(i + 2) == '\n') i++;
                i++;
            } else if (c == '=' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                o.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else if (c < 128) o.write(c);
            else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                o.write(b, 0, b.length);
            }
        }
        return o.toByteArray();
    }

    /** Giải mã tiêu đề dạng =?UTF-8?B?...?= hoặc =?UTF-8?Q?...?= */
    static String dec(String s) {
        s = s.replaceAll("(\\?=)\\s+(=\\?)", "$1$2");
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String r;
            try {
                byte[] b = m.group(2).equalsIgnoreCase("B") ? Base64.getMimeDecoder().decode(m.group(3)) : qp(m.group(3).replace('_', ' '));
                r = new String(b, Charset.forName(m.group(1)));
            } catch (Exception e) {
                r = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // =================================================================== TIỆN ÍCH
    static void bg(Runnable r) {
        Thread t = new Thread(r, "net");
        t.setDaemon(true);
        t.start();
    }

    void status(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.put("swing.boldMetal", Boolean.FALSE);
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            for (Object k : Collections.list(UIManager.getDefaults().keys()))
                if (k.toString().endsWith(".font"))
                    UIManager.put(k, new javax.swing.plaf.FontUIResource(f(Font.PLAIN, 13)));
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new MailClient().setVisible(true));
    }
}