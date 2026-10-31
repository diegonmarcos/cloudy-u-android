package com.diegonmarcos.superapp.uiicons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * One catalog entry (#1160): the glyph and how the bar draws it. [tinted] = the bar colours it (the selected / idle ink),
 * which is every ordinary icon; false = a BRAND icon drawn in its own colours (the assistant's gradient), left untinted.
 */
public class UiIcon(public val vector: ImageVector, public val tinted: Boolean = true)

/**
 * THE icon catalog of the fleet's navigation (#1160): a declared icon name (a Material symbol in snake_case, a semantic name
 * such as `profile` / `configs` / `ai`, or a name an app declared before the catalog existed) to its glyph. It is the single
 * source of every nav-bar icon in every app on the shared nav libs; an app's nav declaration reaches it through
 * libs:bottomnav's `islandEntries()` / `navEntry()` and never names a material icon itself (the nav-icons guard).
 *
 * A nav item ALWAYS draws a glyph: a name this table does not hold draws [FALLBACK] (never a letter or nothing), and [has] is
 * what the guard tests hold every declaration to. Add a name here, once, and every app has it.
 */
public object UiIcons {
    /** The semantic names (#1160), for code that needs one without spelling the string. */
    public object Names {
        public const val INBOX: String = "inbox"
        public const val PROFILE: String = "profile"
        public const val CONFIGS: String = "configs"
        public const val AI: String = "ai"
        public const val ASSISTANT: String = "assistant"
        public const val SEARCH: String = "search"
        public const val SETTINGS: String = "settings"
        public const val HOME: String = "home"
    }

    /** `.ai-nav-icon`: linear-gradient(135deg, #ff416c, #8a2387, #24d292), Cloud Search's assistant mark. */
    public val BRAND_GRADIENT: List<Color> = listOf(Color(0xFFFF416C), Color(0xFF8A2387), Color(0xFF24D292))

    // Material's auto_awesome (the sparkles) path, filled with the brand gradient across the 24x24 viewport.
    private const val SPARKLES =
        "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z" +
            "M11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5z"

    private val assistantMark: ImageVector by lazy {
        ImageVector.Builder("ui_icons_assistant", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(SPARKLES),
                fill = Brush.linearGradient(BRAND_GRADIENT, start = Offset(0f, 0f), end = Offset(24f, 24f)),
            )
            .build()
    }

    /** The tinted entries: name to glyph. */
    private val tintedTable: Map<String, ImageVector> by lazy {
        mapOf(
        "event" to Icons.Outlined.Event,
        "calendar_month" to Icons.Outlined.CalendarMonth,
        "palette" to Icons.Outlined.Palette,
        "inbox" to Icons.Outlined.Inbox,
        // #1189 Cloud Mail's folders and labels in the side menu (their roles; the account's own folders draw "folder"/"label").
        "send" to Icons.Outlined.Send,
        "drafts" to Icons.Outlined.Drafts,
        "delete" to Icons.Outlined.Delete,
        "archive" to Icons.Outlined.Archive,
        "report" to Icons.Outlined.Report,
        "label" to Icons.Outlined.Label,
        "rss_feed" to Icons.Outlined.RssFeed,
        "checklist" to Icons.Outlined.Checklist,
        "home" to Icons.Outlined.Home,
        "forum" to Icons.Outlined.Forum,
        "contacts" to Icons.Outlined.Contacts,
        "chat" to Icons.Outlined.Chat,
        "mark_email_unread" to Icons.Outlined.MarkEmailUnread,
        "edit" to Icons.Outlined.Edit,
        "code" to Icons.Outlined.Code,
        "visibility" to Icons.Outlined.Visibility,
        "vertical_split" to Icons.Outlined.VerticalSplit,
        "accessibility_new" to Icons.Outlined.AccessibilityNew,
        "monitor_heart" to Icons.Outlined.MonitorHeart,
        "bedtime" to Icons.Outlined.Bedtime,
        "bloodtype" to Icons.Outlined.Bloodtype,
        "restaurant" to Icons.Outlined.Restaurant,
        "fitness_center" to Icons.Outlined.FitnessCenter,
        "directions_run" to Icons.Outlined.DirectionsRun,
        "badge" to Icons.Outlined.Badge,
        "route" to Icons.Outlined.Route,
        "person" to Icons.Outlined.Person,
        "emoji_events" to Icons.Outlined.EmojiEvents,
        "public" to Icons.Outlined.Public,
        "flight" to Icons.Outlined.Flight,
        "account_circle" to Icons.Outlined.AccountCircle,
        "work" to Icons.Outlined.Work,
        "rocket_launch" to Icons.Outlined.RocketLaunch,
        "assessment" to Icons.Outlined.Assessment,
        "account_balance_wallet" to Icons.Outlined.AccountBalanceWallet,
        "folder" to Icons.Outlined.Folder,
        "payments" to Icons.Outlined.Payments,
        "account_balance" to Icons.Outlined.AccountBalance,
        "pie_chart" to Icons.Outlined.PieChart,
        "trending_up" to Icons.Outlined.TrendingUp,
        "manage_accounts" to Icons.Outlined.ManageAccounts,
        "settings" to Icons.Outlined.Settings,
        "smartphone" to Icons.Outlined.Smartphone,
        "cloud" to Icons.Outlined.Cloud,
        "apps" to Icons.Outlined.Apps,
        "dns" to Icons.Outlined.Dns,
        "computer" to Icons.Outlined.Computer,
        "inventory_2" to Icons.Outlined.Inventory2,
        "bar_chart" to Icons.Outlined.BarChart,
        "backup" to Icons.Outlined.Backup,
        "info" to Icons.Outlined.Info,
        "luggage" to Icons.Outlined.Luggage,
        "map" to Icons.Outlined.Map,
        "timeline" to Icons.Outlined.Timeline,
        "directions_walk" to Icons.Outlined.DirectionsWalk,
        "my_location" to Icons.Outlined.MyLocation,
        "api" to Icons.Outlined.Api,
        "update" to Icons.Outlined.Update,
        "cached" to Icons.Outlined.Cached,
        "layers" to Icons.Outlined.Layers,
        "auto_fix_high" to Icons.Outlined.AutoFixHigh,
        "language" to Icons.Outlined.Language,
        "content_paste" to Icons.Outlined.ContentPaste,
        "android" to Icons.Outlined.Android,
        "menu_book" to Icons.Outlined.MenuBook,
        "notifications" to Icons.Outlined.Notifications,
        "admin_panel_settings" to Icons.Outlined.AdminPanelSettings,
        "terminal" to Icons.Outlined.Terminal,
        "desktop_windows" to Icons.Outlined.DesktopWindows,
        "key" to Icons.Outlined.Key,
        "hub" to Icons.Outlined.Hub,
        "vpn_key" to Icons.Outlined.VpnKey,
        "token" to Icons.Outlined.Token,
        "verified_user" to Icons.Outlined.VerifiedUser,
        "file_upload" to Icons.Outlined.FileUpload,
        "storage" to Icons.Outlined.Storage,
        "devices" to Icons.Outlined.Devices,
        "lan" to Icons.Outlined.Lan,
        "edit_note" to Icons.Outlined.EditNote,
        "compare_arrows" to Icons.Outlined.CompareArrows,
        "insights" to Icons.Outlined.Insights,
        "link" to Icons.Outlined.Link,
        "lock" to Icons.Outlined.Lock,
        "login" to Icons.Outlined.Login,
        "download_done" to Icons.Outlined.DownloadDone,
        "build" to Icons.Outlined.Build,
        "swap_horiz" to Icons.Outlined.SwapHoriz,
        "fingerprint" to Icons.Outlined.Fingerprint,
        "auto_awesome" to Icons.Outlined.AutoAwesome,
        "memory" to Icons.Outlined.Memory,
        "account_tree" to Icons.Outlined.AccountTree,
        "table_chart" to Icons.Outlined.TableChart,
        "smart_toy" to Icons.Outlined.SmartToy,
        "miscellaneous_services" to Icons.Outlined.MiscellaneousServices,
        "schedule" to Icons.Outlined.Schedule,
        "bolt" to Icons.Outlined.Bolt,
        "tab" to Icons.Outlined.Tab,
        // #1369 Obsidian mobile's history bar (libs:bottomnav HistoryBarItem): Back, Forward, New, Menu.
        "chevron_left" to Icons.Outlined.ChevronLeft,
        "chevron_right" to Icons.Outlined.ChevronRight,
        "add_circle" to Icons.Outlined.AddCircleOutline,
        "menu" to Icons.Outlined.Menu,
        "mail" to Icons.Outlined.Mail,
        "search" to Icons.Outlined.Search,
        "share" to Icons.Outlined.Share,
        "wallet" to Icons.Outlined.Wallet,
        "description" to Icons.Outlined.Description,
        "security" to Icons.Outlined.Security,
        "router" to Icons.Outlined.Router,
        "download" to Icons.Outlined.Download,
        "sync" to Icons.Outlined.Sync,
        "explore" to Icons.Outlined.Explore,
        "navigation" to Icons.Outlined.Navigation,
        "place" to Icons.Outlined.Place,
        "folder_open" to Icons.Outlined.FolderOpen,
        "groups" to Icons.Outlined.Groups,
        "speed" to Icons.Outlined.Speed,
        "person_add" to Icons.Outlined.PersonAdd,
        "history" to Icons.Outlined.History,
        "play_circle" to Icons.Outlined.PlayCircle,
        "bookmark" to Icons.Outlined.Bookmark,
        "star" to Icons.Outlined.Star,
        "sms" to Icons.Outlined.Sms,
        // Drawable-style names the Me, C3 and Drive declarations already use (enforced by those apps' own tests), as Material glyphs.
        "ic_p_health_body" to Icons.Outlined.AccessibilityNew,
        "ic_p_health_vitals" to Icons.Outlined.MonitorHeart,
        "ic_p_health_sleep" to Icons.Outlined.Bedtime,
        "ic_p_blood" to Icons.Outlined.Bloodtype,
        "ic_p_health_nutrition" to Icons.Outlined.Restaurant,
        "ic_gym" to Icons.Outlined.FitnessCenter,
        "ic_p_health_activity" to Icons.Outlined.DirectionsRun,
        "ic_p_cv" to Icons.Outlined.Badge,
        "ic_p_roadmap" to Icons.Outlined.Route,
        "ic_nav_me" to Icons.Outlined.Person,
        "ic_p_pursuits" to Icons.Outlined.EmojiEvents,
        "ic_world" to Icons.Outlined.Public,
        "ic_nav_trips" to Icons.Outlined.Flight,
        "ic_p_identities" to Icons.Outlined.AccountCircle,
        "ic_p_cal_day" to Icons.Outlined.Checklist,
        "ic_nav_home" to Icons.Outlined.Home,
        "ic_nav_work" to Icons.Outlined.Work,
        "ic_p_c3_reports" to Icons.Outlined.Assessment,
        "ic_wallet" to Icons.Outlined.AccountBalanceWallet,
        "ic_p_folder" to Icons.Outlined.Folder,
        "ic_fin" to Icons.Outlined.Payments,
        "ic_p_accounts" to Icons.Outlined.AccountBalance,
        "ic_nav_configs" to Icons.Outlined.Settings,
        "ic_p_sol_cloud" to Icons.Outlined.Cloud,
        "ic_p_c3_vms" to Icons.Outlined.Computer,
        "ic_p_c3_stack" to Icons.Outlined.Layers,
        "ic_wg" to Icons.Outlined.Lan,
        "ic_link_tile" to Icons.Outlined.Link,
        "ic_home" to Icons.Outlined.Home,
        "ic_robot" to Icons.Outlined.SmartToy,
        "ic_p_c3_workflows" to Icons.Outlined.AccountTree,
        "ic_p_c3_health" to Icons.Outlined.MonitorHeart,
        "ic_settings" to Icons.Outlined.Settings,
        "ic_code" to Icons.Outlined.Code,
        "ic_rss" to Icons.Outlined.RssFeed,
        "ic_p_logs" to Icons.Outlined.Description,
        "help_outline" to Icons.Outlined.Info,
        "commit" to Icons.Outlined.Code,
        // Semantic names (#1160): the vocabulary a nav declaration may use for an app-independent meaning.
        "profile" to Icons.Outlined.AccountCircle,
        "configs" to Icons.Outlined.Settings,
        "ai" to Icons.Outlined.AutoAwesome,
        "more" to Icons.Outlined.MoreVert,
        // Section and page names the apps declared before the catalog existed, each with the glyph its app drew (#1160).
        "personal" to Icons.Outlined.AccountCircle,
        "data" to Icons.Outlined.Key,
        "connect" to Icons.Outlined.Share,
        "activity" to Icons.Outlined.Notifications,
        "favourites" to Icons.Outlined.Star,
        "tabs" to Icons.Outlined.Tab,
        "browser" to Icons.Outlined.Public,
        "calculate" to Icons.Outlined.Calculate,
        "psychology" to Icons.Outlined.Psychology,
        "mic" to Icons.Outlined.Mic,
        "camera" to Icons.Outlined.PhotoCamera,
        "ask" to Icons.Outlined.QuestionAnswer,
        "clock" to Icons.Outlined.AccessTime,
        "swap" to Icons.Outlined.SwapHoriz,
        "tools" to Icons.Outlined.Build,
        "chart" to Icons.Outlined.ShowChart,
        "rss" to Icons.Outlined.RssFeed,
        "tasks" to Icons.Outlined.TaskAlt,
        "video" to Icons.Outlined.Videocam,
        "ic_nav_health" to Icons.Outlined.MonitorHeart,
        "ic_nav_study" to Icons.Outlined.School,
        "ic_nav_achieves" to Icons.Outlined.EmojiEvents,
        "ic_nav_buro" to Icons.Outlined.Business,
        "routes" to Icons.Outlined.Route,
        "places" to Icons.Outlined.Place,
        "explored" to Icons.Outlined.Explore,
        "me" to Icons.Outlined.AccountCircle,
        "llc" to Icons.Outlined.Business,
        "agents" to Icons.Outlined.SmartToy,
        "reports" to Icons.Outlined.Assessment,
        "phone" to Icons.Outlined.PhoneAndroid,
        "feed" to Icons.Outlined.RssFeed,
        "access" to Icons.Outlined.Security,
        "calendar" to Icons.Outlined.DateRange,
        "costs" to Icons.Outlined.Payments,
        "documents" to Icons.Outlined.Description,
        "ids" to Icons.Outlined.Badge,
        "pay" to Icons.Outlined.Payments,
        "vcards" to Icons.Outlined.Contacts,
        "events" to Icons.Outlined.ConfirmationNumber,
        "config" to Icons.Outlined.Settings,
        "writer" to Icons.Outlined.Edit,
        // Cloud Me > Health plan pages.
        "health_bio" to Icons.Outlined.MonitorWeight,
        "health_circadian" to Icons.Outlined.NightsStay,
        "health_biomarkers" to Icons.Outlined.Biotech,
        "health_objectives" to Icons.Outlined.Flag,
        "health_matrix" to Icons.Outlined.GridOn,
        "health_culinary" to Icons.Outlined.LocalDining,
        "health_clash" to Icons.Outlined.Science,
        "health_thermal" to Icons.Outlined.Thermostat,
        "health_hemo" to Icons.Outlined.WaterDrop,
        "health_shop" to Icons.Outlined.ShoppingCart,
        "health_dossier" to Icons.Outlined.Summarize,
        "health_timeline" to Icons.Outlined.Timeline,
        // Cloud Calc > Toolkit and Measure > Sensors pages.
        "flashlight_on" to Icons.Outlined.FlashlightOn,
        "keyboard_voice" to Icons.Outlined.KeyboardVoice,
        "sensors" to Icons.Outlined.Sensors,
        // #1221 Configs > Fleet > Bluetooth and its five tabs (Find, Connect, Interact use the next three; Debug and Dev draw ic_p_logs / ic_code).
        "ic_bluetooth" to Icons.Outlined.Bluetooth,
        "bluetooth_find" to Icons.Outlined.BluetoothSearching,
        "bluetooth_connected" to Icons.Outlined.BluetoothConnected,
        // #1264 Cloud Calc > Measure: the WiFi, Bluetooth and NFC pages.
        "wifi" to Icons.Outlined.Wifi,
        "bluetooth" to Icons.Outlined.Bluetooth,
        "nfc" to Icons.Outlined.Nfc,
        )
    }

    /** The brand entries (#1160): drawn in their own colours, never tinted. */
    private val brandTable: Map<String, ImageVector> by lazy {
        mapOf(
            "assistant" to assistantMark,
        )
    }

    public val FALLBACK: ImageVector get() = Icons.Outlined.Tab

    public fun has(name: String): Boolean = name.isNotBlank() && (tintedTable.containsKey(name) || brandTable.containsKey(name))

    /** The entry for [name]; an unknown name is the tinted [FALLBACK], never null. */
    public fun icon(name: String): UiIcon =
        brandTable[name]?.let { UiIcon(it, tinted = false) } ?: UiIcon(tintedTable[name] ?: FALLBACK, tinted = true)

    public fun vector(name: String): ImageVector = icon(name).vector

    /** False for a brand icon (draw it as is); true for everything else, including an unknown name. */
    public fun isTinted(name: String): Boolean = name !in brandTable

    public fun names(): Set<String> = tintedTable.keys + brandTable.keys

    @Composable
    public fun painter(name: String): Painter = rememberVectorPainter(vector(name))
}
