package com.m2y.tv.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.m2y.tv.R
import com.m2y.tv.data.Cat
import com.m2y.tv.data.Item
import com.m2y.tv.data.Kind
import kotlinx.coroutines.CoroutineScope

/** One line of the category rail or of the side list (EPG, episodes). */
class Row(val text: CharSequence, val header: Boolean = false, val active: Boolean = false, val tag: Any? = null)

class TextAdapter(private val onClick: (Row) -> Unit) : RecyclerView.Adapter<TextAdapter.H>() {
    var rows: List<Row> = emptyList()
        private set

    class H(val t: TextView) : RecyclerView.ViewHolder(t)

    @SuppressLint("NotifyDataSetChanged") // whole list replaced
    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    /** Moves the highlight to the row whose tag equals [tag] without rebinding the others. */
    fun activate(tag: Any?) {
        val next = rows.map { if (it.header) it else Row(it.text, false, it.tag == tag, it.tag) }
        val changed = rows.indices.filter { rows[it].active != next[it].active }
        rows = next
        changed.forEach(::notifyItemChanged)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val h = H(LayoutInflater.from(parent.context).inflate(R.layout.row_text, parent, false) as TextView)
        h.t.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && !rows[p].header) onClick(rows[p])
        }
        return h
    }

    override fun onBindViewHolder(h: H, position: Int) {
        val r = rows[position]
        h.t.text = r.text
        h.t.isActivated = r.active
        h.t.isClickable = !r.header && r.tag != null
        h.t.alpha = if (r.header) 0.7f else 1f
    }

    companion object {
        fun cats(list: List<Cat>, selected: String?) = list.map { Row(it.name, active = it.key == selected, tag = it.key) }
    }
}

/**
 * Channel / movie / series list. Holds only the pages loaded so far; asks for the next page near the end.
 */
class ItemAdapter(
    private val scope: CoroutineScope,
    private val images: Images,
    private val onClick: (Item) -> Unit,
    private val onLongClick: (Item) -> Unit,
    private val onNearEnd: () -> Unit,
) : RecyclerView.Adapter<ItemAdapter.H>() {
    val items = ArrayList<Item>()
    private var favs: Set<String> = emptySet()
    private var current: String? = null
    private var kind = Kind.LIVE

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val star: View = v.findViewById(R.id.star)
    }

    @SuppressLint("NotifyDataSetChanged") // whole list replaced
    fun submit(k: Kind, list: List<Item>, favKeys: Set<String>) {
        kind = k
        favs = favKeys
        items.clear()
        items += list
        notifyDataSetChanged()
    }

    fun append(list: List<Item>) {
        val start = items.size
        items += list
        notifyItemRangeInserted(start, list.size)
    }

    fun setFavs(keys: Set<String>, changedExt: String) {
        favs = keys
        notifyMatching(changedExt)
    }

    fun setCurrent(ext: String?) {
        val old = current
        current = ext
        old?.let(::notifyMatching)
        ext?.let(::notifyMatching)
    }

    fun indexOf(ext: String?) = items.indexOfFirst { it.ext == ext }

    private fun notifyMatching(ext: String) = items.forEachIndexed { i, it -> if (it.ext == ext) notifyItemChanged(i) }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val h = H(LayoutInflater.from(parent.context).inflate(R.layout.row_item, parent, false))
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(items[it]) } }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onLongClick(items[it]) }
            true
        }
        return h
    }

    override fun onBindViewHolder(h: H, position: Int) {
        val it = items[position]
        val live = kind == Kind.LIVE
        val d = h.itemView.resources.displayMetrics.density
        val w = ((if (live) 56 else 40) * d).toInt()
        val ht = ((if (live) 40 else 60) * d).toInt()
        h.logo.layoutParams.let { lp ->
            if (lp.width != w) {
                lp.width = w
                lp.height = ht
                h.logo.layoutParams = lp
                h.logo.scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            }
        }
        h.num.visibility = if (live && it.num > 0) View.VISIBLE else View.GONE
        h.num.text = it.num.toString()
        h.name.text = it.name
        h.meta.text = it.rating?.let { r -> "★ $r" }.orEmpty()
        h.star.visibility = if (it.ext in favs) View.VISIBLE else View.GONE
        h.itemView.isActivated = it.ext == current
        images.load(scope, h.logo, it.logo, w, ht, opaque = !live)
        if (position >= items.size - PREFETCH) onNearEnd()
    }

    private companion object {
        const val PREFETCH = 60
    }
}
