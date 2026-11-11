package top.rootu.dddplayer.ui

import android.app.Dialog
import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.ui.adapter.ChoiceAdapter
import top.rootu.dddplayer.ui.adapter.MultiChoiceAdapter

/**
 * Диалоги выбора из списка (экраны настроек): одно значение или несколько флажков.
 */
object ChoiceDialog {

    /** @param currentIndex отмеченный пункт, -1 - без отметки (фокус на первом пункте) */
    fun show(context: Context, title: String, items: List<String>, currentIndex: Int, onSelected: (Int) -> Unit): Dialog {
        val dialog = Dialog(context, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = title

        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = ChoiceAdapter(items, currentIndex) { idx ->
            onSelected(idx)
            dialog.dismiss()
        }

        dialog.show()
        val focusIndex = currentIndex.coerceAtLeast(0)
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(focusIndex, 0)
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus() }, 50)
        }
        return dialog
    }

    /**
     * Флажки применяются сразу при переключении, диалог закрывается кнопкой "Назад".
     * [enabled] - false у пунктов, которые нельзя переключить.
     */
    fun showMulti(
        context: Context,
        title: String,
        items: List<String>,
        checked: BooleanArray,
        enabled: BooleanArray? = null,
        onChanged: (BooleanArray) -> Unit
    ): Dialog {
        val dialog = Dialog(context, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = title

        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = MultiChoiceAdapter(items, checked.copyOf(), enabled, onChanged)

        dialog.show()
        recycler.post {
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 50)
        }
        return dialog
    }
}
