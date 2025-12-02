package com.example.vulpinenotes

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView

class LanguageAdapter(
    context: Context,
    resource: Int,
    private val languages: List<Language>
) : ArrayAdapter<Language>(context, resource, languages) {

    private val allLanguages = languages.toList()

    override fun getCount(): Int = allLanguages.size
    override fun getItem(position: Int): Language? = allLanguages.getOrNull(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    private fun createView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            R.layout.item_language, parent, false
        )

        val language = getItem(position) ?: return view
        view.findViewById<TextView>(R.id.language_name).text = language.name
        view.findViewById<ImageView>(R.id.flag_icon).setImageResource(language.flagResId)

        return view
    }

    // возвращаем Filter который ничего не фильтрует
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            return FilterResults().apply {
                values = allLanguages
                count = allLanguages.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
        }
    }
}