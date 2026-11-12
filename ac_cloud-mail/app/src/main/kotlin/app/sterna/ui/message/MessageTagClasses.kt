package app.sterna.ui.message

/**
 * The two tag families of the reading panel (#1661), decided as data so a test can execute the rule.
 *
 * A tag is classified by the NAME THE READER SEES (a mailbox's display name, or a user keyword),
 * by its first character only:
 *
 *   META     the name starts with a letter A-Z ("A*" ... "Z*" read as a glob: "Fa Gov", "Inbox")
 *   PROJECT  the name starts with a digit 1-9 ("1*" ... "9*": "1 Website", "42 - Ops")
 *   OTHER    anything else (a leading 0, an emoji, punctuation, an empty name)
 *
 * OTHER tags ride along with the Project section, so a tag never disappears behind a family it does
 * not belong to. This is the ONE classification function of the app: the strip under the sender and
 * the full message details both ask it, so they cannot disagree.
 */
enum class TagClass { META, PROJECT, OTHER }

/** The family [name] belongs to. Null, blank and non-ASCII leading characters are [TagClass.OTHER]. */
fun classifyTagName(name: String?): TagClass {
    val first = name?.trimStart()?.firstOrNull() ?: return TagClass.OTHER
    return when (first) {
        in 'A'..'Z', in 'a'..'z' -> TagClass.META
        in '1'..'9' -> TagClass.PROJECT
        else -> TagClass.OTHER
    }
}

/** The two sections the reading panel draws, in the order it draws them. */
enum class TagSectionKind { PROJECT, META }

/** One section and the tags it holds. Never empty: [tagSections] drops a section with no tag. */
class TagSection(val kind: TagSectionKind, val tags: List<MessageTag>)

/**
 * [tags] split into the Project section (project tags, then the unclassified ones) and the Meta
 * section, Project first. The incoming order is kept inside each family, and EVERY tag lands in
 * exactly one section. A section with nothing in it is not returned, so it costs no height.
 */
fun tagSections(tags: List<MessageTag>): List<TagSection> {
    val project = mutableListOf<MessageTag>()
    val other = mutableListOf<MessageTag>()
    val meta = mutableListOf<MessageTag>()
    for (tag in tags) {
        when (classifyTagName(tag.label)) {
            TagClass.PROJECT -> project += tag
            TagClass.META -> meta += tag
            TagClass.OTHER -> other += tag
        }
    }
    val sections = mutableListOf<TagSection>()
    if (project.isNotEmpty() || other.isNotEmpty()) {
        sections += TagSection(TagSectionKind.PROJECT, project + other)
    }
    if (meta.isNotEmpty()) sections += TagSection(TagSectionKind.META, meta)
    return sections
}

/** The state a section opens in: Project tags are shown, Meta tags sit behind "See more". */
fun tagSectionExpandedByDefault(kind: TagSectionKind): Boolean = kind == TagSectionKind.PROJECT
