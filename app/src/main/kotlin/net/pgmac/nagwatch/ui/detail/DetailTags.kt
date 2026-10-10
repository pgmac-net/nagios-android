// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

object DetailTags {
    const val TITLE = "detail_title"
    const val BACK = "detail_back"
    const val LIST = "detail_list"
    const val HEADER = "detail_header"
    const val SINCE = "detail_since"
    const val AS_OF = "detail_as_of"
    const val LOADING = "detail_loading"
    const val ERROR = "detail_error"
    const val BANNER_FAILED = "detail_banner_failed"
    const val BANNER_STALE = "detail_banner_stale"
    const val OUTPUT = "detail_output"
    const val LONG_OUTPUT = "detail_long_output"
    const val LONG_OUTPUT_TOGGLE = "detail_long_output_toggle"
    const val PERF = "detail_perf"
    const val PERF_TOGGLE = "detail_perf_toggle"
    const val FLAGS = "detail_flags"
    const val HOST_LINK = "detail_host_link"
    const val COMMENTS = "detail_comments"
    const val COMMENTS_ALL = "detail_comments_all"
    const val DOWNTIMES = "detail_downtimes"
    const val OPEN_IN_NAGIOS = "detail_open_in_nagios"

    fun service(description: String) = "detail_service_$description"
}
