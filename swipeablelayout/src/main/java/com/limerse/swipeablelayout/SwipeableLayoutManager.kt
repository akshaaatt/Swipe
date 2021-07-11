package com.limerse.swipeablelayout

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import kotlin.math.max
import kotlin.math.min

class SwipeableLayoutManager : RecyclerView.LayoutManager() {
    var maxShowCount = 3
    var scaleGap = 0.1f
    var transYGap = 0
    var angle = 10
    var animationDuratuion: Long = 500

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        val itemCount = itemCount
        if (itemCount < 1) {
            return
        }
        var startPosition = min(maxShowCount, itemCount) - 1
        startPosition = if (startPosition > 0) startPosition else 0
        for (position in startPosition downTo 0) {
            val view = recycler.getViewForPosition(position)
            addView(view)
            measureChildWithMargins(view, 0, 0)
            val widthSpace = width - getDecoratedMeasuredWidth(view)
            val heightSpace = height - getDecoratedMeasuredHeight(view)
            layoutDecorated(
                view, widthSpace / 2, heightSpace / 2,
                widthSpace / 2 + getDecoratedMeasuredWidth(view),
                heightSpace / 2 + getDecoratedMeasuredHeight(view)
            )
            if (position > 0) {
                view.scaleX = validateScale(1 - scaleGap * position)
                if (position < maxShowCount - 1) {
                    view.translationY = validateScale((transYGap * position).toFloat())
                    view.scaleY = validateScale(1 - scaleGap * position)
                } else {
                    view.translationY = validateTranslation((transYGap * (position - 1)).toFloat())
                    view.scaleY = validateScale(1 - scaleGap * (position - 1))
                }
            }
        }
    }

    private fun validateTranslation(value: Float): Float {
        return max(0f, value)
    }

    private fun validateScale(value: Float): Float {
        return max(0f, min(1f, value))
    }

    /**
     * max views rendered under recycler view
     *
     * @param maxShowCount default value 3
     */
    fun setMaxShowCount(maxShowCount: Int): SwipeableLayoutManager {
        this.maxShowCount = max(maxShowCount, 1)
        return this
    }

    /**
     * Percentage of scaling views behind top view
     *
     * @param scaleGap min value = 0 max value = 1 default value = 0.1
     */
    fun setScaleGap(scaleGap: Float): SwipeableLayoutManager {
        this.scaleGap = min(max(0f, scaleGap), 1f)
        return this
    }

    /**
     * Represents value in used to translate center of views behind top view  and create nice card
     * stack effect
     *
     * @param transYGap default value 0
     */
    fun setTransYGap(transYGap: Int): SwipeableLayoutManager {
        this.transYGap = transYGap
        return this
    }

    /**
     * Angle in degres used for rotation of top view while swiping left or right
     */
    fun setAngle(angle: Int): SwipeableLayoutManager {
        this.angle = angle
        return this
    }

    /**
     * Animation duration after swiping view
     */
    fun setAnimationDuratuion(animationDuratuion: Long): SwipeableLayoutManager {
        this.animationDuratuion = max(1, animationDuratuion)
        return this
    }
}