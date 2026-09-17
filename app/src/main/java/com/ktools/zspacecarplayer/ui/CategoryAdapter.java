package com.ktools.zspacecarplayer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.CategoryItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单与分类网格平铺适配器。
 * 顺序: 全部歌曲 -> 红心收藏 -> 播放最多 -> 文件夹分类 -> 音乐流派
 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder> {

    private List<CategoryItem> categories = new ArrayList<>();
    private int selectedPosition = 0;
    private OnCategoryClickListener listener;

    public interface OnCategoryClickListener {
        void onCategoryClick(CategoryItem category, int position);
    }

    public void setOnCategoryClickListener(OnCategoryClickListener listener) {
        this.listener = listener;
    }

    public void setCategories(List<CategoryItem> categories) {
        // 按名字重定位 selectedPosition (2026-09-16 车机复现修复): 媒体库刷新后重建的
        // 文件夹/流派卡片顺序由 LinkedHashMap 的歌曲遍历顺序决定, 服务器返回顺序变化
        // (或曲目增减) 会让同一个歌单挪到不同下标。旧写法只保留裸下标, 刷新后
        // getSelectedCategory() 会静默返回挪位后占据该下标的另一个歌单, 导致左侧列表
        // 被错误覆盖成不相关的歌单——即使实际播放队列的来源歌单没有变化。
        String selectedName = getSelectedCategoryName();
        this.categories = (categories != null) ? new ArrayList<>(categories) : new ArrayList<CategoryItem>();
        if (selectedName != null) {
            int resolved = indexOfName(selectedName);
            selectedPosition = (resolved >= 0) ? resolved : 0;
        } else if (selectedPosition >= this.categories.size()) {
            selectedPosition = 0;
        }
        notifyDataSetChanged();
    }

    /** 当前选中歌单的名字 (刷新前保存, 用于刷新后按名字重定位), 无选中项时 null。 */
    private String getSelectedCategoryName() {
        if (selectedPosition >= 0 && selectedPosition < categories.size()) {
            return categories.get(selectedPosition).getName();
        }
        return null;
    }

    private int indexOfName(String name) {
        for (int i = 0; i < categories.size(); i++) {
            if (name.equals(categories.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    public List<CategoryItem> getCategories() {
        return categories;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public CategoryItem getSelectedCategory() {
        if (selectedPosition >= 0 && selectedPosition < categories.size()) {
            return categories.get(selectedPosition);
        }
        return null;
    }

    public void setSelectedPosition(int position) {
        if (position >= 0 && position < categories.size()) {
            int prev = selectedPosition;
            selectedPosition = position;
            notifyItemChanged(prev);
            notifyItemChanged(selectedPosition);
        }
    }

    public void setSelectedCategoryName(String name) {
        if (name == null) return;
        for (int i = 0; i < categories.size(); i++) {
            if (name.equals(categories.get(i).getName())) {
                setSelectedPosition(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_grid, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final CategoryViewHolder holder, final int position) {
        final CategoryItem category = categories.get(position);
        String name = category.getName();
        String id = category.getId();

        String displayIcon = "📁";
        int bgRes = R.drawable.bg_thumb_2;

        if ("fav".equals(id) || (name != null && name.contains("红心"))) {
            displayIcon = "❤️";
            bgRes = R.drawable.bg_thumb_4;
        } else if ("most_played".equals(id) || (name != null && name.contains("播放最多"))) {
            displayIcon = "🔥";
            bgRes = R.drawable.bg_thumb_5;
        } else if ("all".equals(id) || (name != null && name.contains("全部歌曲"))) {
            displayIcon = "🎵";
            bgRes = R.drawable.bg_thumb_1;
        } else if (name != null && name.startsWith("📁 ")) {
            displayIcon = "📁";
            bgRes = R.drawable.bg_thumb_2;
            name = name.substring(3); // 去掉前缀 emoji
        } else {
            displayIcon = "🏷️";
            bgRes = R.drawable.bg_thumb_3;
        }

        holder.tvCardIcon.setText(displayIcon);
        holder.tvCardIcon.setBackgroundResource(bgRes);
        holder.tvCardTitle.setText(name);
        holder.tvCardSub.setText(category.getSongCount() + " 首歌曲");

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    setSelectedPosition(pos);
                    if (listener != null) {
                        listener.onCategoryClick(category, pos);
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        TextView tvCardIcon;
        TextView tvCardTitle;
        TextView tvCardSub;

        public CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCardIcon = itemView.findViewById(R.id.tvCardIcon);
            tvCardTitle = itemView.findViewById(R.id.tvCardTitle);
            tvCardSub = itemView.findViewById(R.id.tvCardSub);
        }
    }
}
