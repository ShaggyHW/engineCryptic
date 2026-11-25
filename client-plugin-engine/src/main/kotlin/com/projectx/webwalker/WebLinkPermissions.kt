package com.projectx.webwalker

import com.projectx.traversal.pathing.RingPermissions

/** Which of the measured links this account may take now, judged by each link's requirements. */
@Deprecated("Routes come from navpath, which checks requirements itself. Kept so scripts built against it still load.")
class WebLinkPermissions private constructor(private val rules: RingPermissions) {
    fun allows(link: WebLink): Boolean = rules.allows(link)

    companion object {
        /** Every link allowed. */
        val UNRESTRICTED = WebLinkPermissions(RingPermissions.UNRESTRICTED)

        /** Judges every measured link against the account as it stands; read on the game thread. */
        fun snapshot(): WebLinkPermissions = WebLinkPermissions(RingPermissions.snapshotOf(WebLinks.all))
    }
}
