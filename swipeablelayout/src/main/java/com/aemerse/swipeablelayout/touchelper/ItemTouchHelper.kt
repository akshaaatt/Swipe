package com.aemerse.swipeablelayout.touchelper

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.animation.Interpolator
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchUIUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.*
import com.aemerse.swipeablelayout.R
import com.aemerse.swipeablelayout.SwipeableTouchHelperCallback
import com.aemerse.swipeablelayout.touchelper.ItemTouchUIUtilImpl.BaseImpl
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

class ItemTouchHelper
/**
 * Creates an ItemTouchHelper that will work with the given Callback.
 *
 *
 * You can attach ItemTouchHelper to a RecyclerView via
 * [.attachToRecyclerView]. Upon attaching, it will add an item decoration,
 * an onItemTouchListener and a Child attach / detach listener to the RecyclerView.
 *
 * @param callback The Callback which controls the behavior of this touch helper.
 */(
    /**
     * Developer callback which controls the behavior of ItemTouchHelper.
     */
    var mCallback: Callback
) : ItemDecoration(), OnChildAttachStateChangeListener {
    /**
     * Views, whose state should be cleared after they are detached from RecyclerView.
     * This is necessary after swipe dismissing an item. We wait until animator finishes its job
     * to clean these views.
     */
    val mPendingCleanup: MutableList<View> = ArrayList()

    /**
     * Re-use array to calculate dx dy for a ViewHolder
     */
    private val mTmpPosition = FloatArray(2)

    /**
     * Currently selected view holder
     */
    var mSelected: ViewHolder? = null

    /**
     * The reference coordinates for the action start. For drag & drop, this is the time long
     * press is completed vs for swipe, this is the initial touch point.
     */
    var mInitialTouchX = 0f
    var mInitialTouchY = 0f

    /**
     * Set when ItemTouchHelper is assigned to a RecyclerView.
     */
    var mSwipeEscapeVelocity = 0f

    /**
     * Set when ItemTouchHelper is assigned to a RecyclerView.
     */
    var mMaxSwipeVelocity = 0f

    /**
     * The diff between the last event and initial touch.
     */
    var mDx = 0f
    var mDy = 0f

    /**
     * The coordinates of the selected view at the time it is selected. We record these values
     * when action starts so that we can consistently position it even if LayoutManager moves the
     * View.
     */
    var mSelectedStartX = 0f
    var mSelectedStartY = 0f

    /**
     * The pointer we are tracking.
     */
    var mActivePointerId = ACTIVE_POINTER_ID_NONE

    /**
     * Current mode.
     */
    var mActionState = ACTION_STATE_IDLE

    /**
     * The direction flags obtained from unmasking
     * [Callback.getAbsoluteMovementFlags] for the current
     * action state.
     */
    var mSelectedFlags = 0

    /**
     * When a View is dragged or swiped and needs to go back to where it was, we create a Recover
     * Animation and animate it to its location using this custom Animator, instead of using
     * framework Animators.
     * Using framework animators has the side effect of clashing with ItemAnimator, creating
     * jumpy UIs.
     */
    var mRecoverAnimations: MutableList<RecoverAnimation> = ArrayList()
    var mRecyclerView: RecyclerView? = null

    /**
     * Used for detecting fling swipe
     */
    var mVelocityTracker: VelocityTracker? = null

    /**
     * This keeps a reference to the child dragged by the user. Even after user stops dragging,
     * until view reaches its final position (end of recover animation), we keep a reference so
     * that it can be drawn above other children.
     */
    var mOverdrawChild: View? = null

    /**
     * We cache the position of the overdraw child to avoid recalculating it each time child
     * position callback is called. This value is invalidated whenever a child is attached or
     * detached.
     */
    var mOverdrawChildPosition = -1

    /**
     * Used to detect long press.
     */
    var mGestureDetector: GestureDetectorCompat? = null
    private var mSlop = 0

    //re-used list for selecting a swap target
    private var mSwapTargets: MutableList<ViewHolder>? = null

    //re used for for sorting swap targets
    private var mDistances: MutableList<Int>? = null

    /**
     * If drag & drop is supported, we use child drawing order to bring them to front.
     */
    private var mChildDrawingOrderCallback: ChildDrawingOrderCallback? = null

    /**
     * Callback for when long press occurs.
     */
    private var mItemTouchHelperGestureListener: ItemTouchHelperGestureListener? = null

    /**
     * Temporary rect instance that is used when we need to lookup Item decorations.
     */
    private var mTmpRect: Rect? = null

    /**
     * When user started to drag scroll. Reset when we don't scroll
     */
    private var mDragScrollStartTimeInMs: Long = 0

    /**
     * When user drags a view to the edge, we start scrolling the LayoutManager as long as View
     * is partially out of bounds.
     */
    val mScrollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (mSelected != null && scrollIfNecessary()) {
                if (mSelected != null) { //it might be lost during scrolling
                    moveIfNecessary(mSelected!!)
                }
                mRecyclerView!!.removeCallbacks(this)
                ViewCompat.postOnAnimation(mRecyclerView!!, this)
            }
        }
    }
    private val mOnItemTouchListener: OnItemTouchListener = object : OnItemTouchListener {
        override fun onInterceptTouchEvent(
            recyclerView: RecyclerView,
            event: MotionEvent
        ): Boolean {
            mGestureDetector!!.onTouchEvent(event)
            if (DEBUG) {
                Log.d(TAG, "intercept: x:" + event.x + ",y:" + event.y + ", " + event)
            }
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                mActivePointerId = event.getPointerId(0)
                mInitialTouchX = event.x
                mInitialTouchY = event.y
                obtainVelocityTracker()
                if (mSelected == null) {
                    val animation = findAnimation(event)
                    if (animation != null) {
                        mInitialTouchX -= animation.mX
                        mInitialTouchY -= animation.mY
                        endRecoverAnimation(animation.mViewHolder, true)
                        if (mPendingCleanup.remove(animation.mViewHolder.itemView)) {
                            mCallback.clearView(mRecyclerView, animation.mViewHolder)
                        }
                        select(animation.mViewHolder, animation.mActionState)
                        updateDxDy(event, mSelectedFlags, 0)
                    }
                }
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                mActivePointerId = ACTIVE_POINTER_ID_NONE
                select(null, ACTION_STATE_IDLE)
            } else if (mActivePointerId != ACTIVE_POINTER_ID_NONE) {
                // in a non scroll orientation, if distance change is above threshold, we
                // can select the item
                val index = event.findPointerIndex(mActivePointerId)
                if (DEBUG) {
                    Log.d(TAG, "pointer index $index")
                }
                if (index >= 0) {
                    checkSelectForSwipe(action, event, index)
                }
            }
            if (mVelocityTracker != null) {
                mVelocityTracker!!.addMovement(event)
            }
            return mSelected != null
        }

        override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
            mGestureDetector!!.onTouchEvent(event)
            if (DEBUG) {
                Log.d(TAG, "on touch: x:$mInitialTouchX,y:$mInitialTouchY, :$event")
            }
            if (mVelocityTracker != null) {
                mVelocityTracker!!.addMovement(event)
            }
            if (mActivePointerId == ACTIVE_POINTER_ID_NONE) {
                return
            }
            val action = event.actionMasked
            val activePointerIndex = event.findPointerIndex(mActivePointerId)
            if (activePointerIndex >= 0) {
                checkSelectForSwipe(action, event, activePointerIndex)
            }
            val viewHolder = mSelected ?: return
            when (action) {
                MotionEvent.ACTION_MOVE -> {

                    // Find the index of the active pointer and fetch its position
                    if (activePointerIndex >= 0) {
                        updateDxDy(event, mSelectedFlags, activePointerIndex)
                        moveIfNecessary(viewHolder)
                        mRecyclerView!!.removeCallbacks(mScrollRunnable)
                        mScrollRunnable.run()
                        mRecyclerView!!.invalidate()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mVelocityTracker != null) {
                        mVelocityTracker!!.clear()
                    }
                    select(null, ACTION_STATE_IDLE)
                    mActivePointerId = ACTIVE_POINTER_ID_NONE
                }
                MotionEvent.ACTION_UP -> {
                    select(null, ACTION_STATE_IDLE)
                    mActivePointerId = ACTIVE_POINTER_ID_NONE
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == mActivePointerId) {
                        // This was our active pointer going up. Choose a new
                        // active pointer and adjust accordingly.
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        mActivePointerId = event.getPointerId(newPointerIndex)
                        updateDxDy(event, mSelectedFlags, pointerIndex)
                    }
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            if (!disallowIntercept) {
                return
            }
            select(null, ACTION_STATE_IDLE)
        }
    }

    /**
     * Attaches the ItemTouchHelper to the provided RecyclerView. If TouchHelper is already
     * attached to a RecyclerView, it will first detach from the previous one. You can call this
     * method with `null` to detach it from the current RecyclerView.
     *
     * @param recyclerView The RecyclerView instance to which you want to add this helper or
     * `null` if you want to remove ItemTouchHelper from the current
     * RecyclerView.
     */
    fun attachToRecyclerView(recyclerView: RecyclerView?) {
        if (mRecyclerView === recyclerView) {
            return  // nothing to do
        }
        if (mRecyclerView != null) {
            destroyCallbacks()
        }
        mRecyclerView = recyclerView
        if (recyclerView != null) {
            val resources = recyclerView.resources
            mSwipeEscapeVelocity =
                resources.getDimension(R.dimen.item_touch_helper_swipe_escape_velocity)
            mMaxSwipeVelocity =
                resources.getDimension(R.dimen.item_touch_helper_swipe_escape_max_velocity)
            setupCallbacks()
        }
    }

    private fun setupCallbacks() {
        val vc = ViewConfiguration.get(mRecyclerView!!.context)
        mSlop = vc.scaledTouchSlop
        mRecyclerView!!.addItemDecoration(this)
        mRecyclerView!!.addOnItemTouchListener(mOnItemTouchListener)
        mRecyclerView!!.addOnChildAttachStateChangeListener(this)
        startGestureDetection()
    }

    private fun destroyCallbacks() {
        mRecyclerView!!.removeItemDecoration(this)
        mRecyclerView!!.removeOnItemTouchListener(mOnItemTouchListener)
        mRecyclerView!!.removeOnChildAttachStateChangeListener(this)
        // clean all attached
        val recoverAnimSize = mRecoverAnimations.size
        for (i in recoverAnimSize - 1 downTo 0) {
            val recoverAnimation = mRecoverAnimations[0]
            mCallback.clearView(mRecyclerView, recoverAnimation.mViewHolder)
        }
        mRecoverAnimations.clear()
        mOverdrawChild = null
        mOverdrawChildPosition = -1
        releaseVelocityTracker()
        stopGestureDetection()
    }

    private fun startGestureDetection() {
        mItemTouchHelperGestureListener = ItemTouchHelperGestureListener()
        mGestureDetector =
            GestureDetectorCompat(mRecyclerView!!.context, mItemTouchHelperGestureListener)
    }

    private fun stopGestureDetection() {
        if (mItemTouchHelperGestureListener != null) {
            mItemTouchHelperGestureListener!!.doNotReactToLongPress()
            mItemTouchHelperGestureListener = null
        }
        if (mGestureDetector != null) {
            mGestureDetector = null
        }
    }

    private fun getSelectedDxDy(outPosition: FloatArray) {
        if (mSelectedFlags and (LEFT or RIGHT) != 0) {
            outPosition[0] = mSelectedStartX + mDx - mSelected!!.itemView.left
        } else {
            outPosition[0] = mSelected!!.itemView.translationX
        }
        if (mSelectedFlags and (UP or DOWN) != 0) {
            outPosition[1] = mSelectedStartY + mDy - mSelected!!.itemView.top
        } else {
            outPosition[1] = mSelected!!.itemView.translationY
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: State) {
        var dx = 0f
        var dy = 0f
        if (mSelected != null) {
            getSelectedDxDy(mTmpPosition)
            dx = mTmpPosition[0]
            dy = mTmpPosition[1]
        }
        mCallback.onDrawOver(c, parent, mSelected, mRecoverAnimations, mActionState, dx, dy)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: State) {
        // we don't know if RV changed something so we should invalidate this index.
        mOverdrawChildPosition = -1
        var dx = 0f
        var dy = 0f
        if (mSelected != null) {
            getSelectedDxDy(mTmpPosition)
            dx = mTmpPosition[0]
            dy = mTmpPosition[1]
        }
        mCallback.onDraw(c, parent, mSelected, mRecoverAnimations, mActionState, dx, dy)
    }

    fun swipe(viewHolder: ViewHolder, direction: Int) {
        val allowedSwipeDirections =
            (mCallback as SwipeableTouchHelperCallback).getAllowedSwipeDirectionsMovementFlags(
                mSelected
            )
        if (direction and allowedSwipeDirections == 0) return
        var targetX = 0
        var targetY = 0
        if (direction == UP) {
            targetY = -mRecyclerView!!.height - 200
        } else if (direction == DOWN) {
            targetY = +mRecyclerView!!.height + 200
        }
        if (direction == RIGHT) {
            targetX = mRecyclerView!!.width + 200
        } else if (direction == LEFT) {
            targetX = -mRecyclerView!!.width - 200
        }
        startSwipe(viewHolder)
        getSelectedDxDy(mTmpPosition)
        val currentTranslateX = mTmpPosition[0]
        val currentTranslateY = mTmpPosition[1]
        val rv: RecoverAnimation = object : RecoverAnimation(
            viewHolder, ANIMATION_TYPE_SWIPE_SUCCESS, ACTION_STATE_SWIPE,
            currentTranslateX, currentTranslateY, targetX.toFloat(), targetY.toFloat()
        ) {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                if (mOverridden) {
                    return
                }
                mPendingCleanup.add(viewHolder.itemView)
                mIsPendingCleanup = true

                // Animation might be ended by other animators during a layout.
                // We defer callback to avoid editing adapter during a layout.
                postDispatchSwipe(this, direction)

                // removed from the list after it is drawn for the last time
                if (mOverdrawChild === viewHolder.itemView) {
                    removeChildDrawingOrderCallbackIfNecessary(viewHolder.itemView)
                }
            }
        }
        val duration = mCallback.getAnimationDuration(
            mRecyclerView!!, ANIMATION_TYPE_SWIPE_SUCCESS,
            targetX - currentTranslateX, targetY - currentTranslateY
        )
        rv.setDuration(duration)
        mRecoverAnimations.add(rv)
        rv.start()
        mSelected = null
        val rvParent = mRecyclerView!!.parent
        rvParent?.requestDisallowInterceptTouchEvent(mSelected != null)
        mCallback.onSelectedChanged(mSelected, mActionState)
        mRecyclerView!!.invalidate()
    }

    /**
     * Starts dragging or swiping the given View. Call with null if you want to clear it.
     *
     * @param selected The ViewHolder to drag or swipe. Can be null if you want to cancel the
     * current action
     * @param actionState The type of action
     */
    fun select(selected: ViewHolder?, actionState: Int) {
        if (selected === mSelected && actionState == mActionState) {
            return
        }
        if (selected !== mSelected && RecoverAnimation.inProgress) return
        mDragScrollStartTimeInMs = Long.MIN_VALUE
        val prevActionState = mActionState
        // prevent duplicate animations
        endRecoverAnimation(selected, true)
        mActionState = actionState
        if (actionState == ACTION_STATE_DRAG) {
            // we remove after animation is complete. this means we only elevate the last drag
            // child but that should perform good enough as it is very hard to start dragging a
            // new child before the previous one settles.
            mOverdrawChild = selected!!.itemView
            addChildDrawingOrderCallback()
        }
        val actionStateMask = (1 shl DIRECTION_FLAG_COUNT + DIRECTION_FLAG_COUNT * actionState) - 1
        var preventLayout = false
        if (mSelected != null) {
            val prevSelected: ViewHolder = mSelected as ViewHolder
            if (prevSelected.itemView.parent != null) {
                val swipeDir =
                    if (prevActionState == ACTION_STATE_DRAG) 0 else swipeIfNecessary(prevSelected)
                releaseVelocityTracker()
                // find where we should animate to
                val targetTranslateX: Float
                val targetTranslateY: Float
                val allowedSwipeDirections =
                    (mCallback as SwipeableTouchHelperCallback).getAllowedSwipeDirectionsMovementFlags(
                        mSelected
                    )
                when (swipeDir) {
                    LEFT -> if (allowedSwipeDirections and LEFT == 0) {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    } else {
                        targetTranslateY = mDy * 2f
                        targetTranslateX = Math.signum(mDx) * (mRecyclerView!!.width + 200)
                    }
                    RIGHT -> if (allowedSwipeDirections and RIGHT == 0) {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    } else {
                        targetTranslateY = mDy * 2f
                        targetTranslateX = Math.signum(mDx) * (mRecyclerView!!.width + 200)
                    }
                    UP -> if (allowedSwipeDirections and UP == 0) {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    } else {
                        targetTranslateY = Math.signum(mDy) * (mRecyclerView!!.height + 200)
                        targetTranslateX = mDx * 2f
                    }
                    DOWN -> if (allowedSwipeDirections and DOWN == 0) {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    } else {
                        targetTranslateY = Math.signum(mDy) * (mRecyclerView!!.height + 200)
                        targetTranslateX = mDx * 2f
                    }
                    START, END -> {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    }
                    else -> {
                        targetTranslateX = 0f
                        targetTranslateY = 0f
                    }
                }
                val animationType: Int = when {
                    prevActionState == ACTION_STATE_DRAG -> {
                        ANIMATION_TYPE_DRAG
                    }
                    swipeDir > 0 -> {
                        ANIMATION_TYPE_SWIPE_SUCCESS
                    }
                    else -> {
                        ANIMATION_TYPE_SWIPE_CANCEL
                    }
                }
                getSelectedDxDy(mTmpPosition)
                val currentTranslateX = mTmpPosition[0]
                val currentTranslateY = mTmpPosition[1]
                val rv: RecoverAnimation = object : RecoverAnimation(
                    prevSelected, animationType, prevActionState, currentTranslateX,
                    currentTranslateY, targetTranslateX, targetTranslateY
                ) {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        if (mOverridden) {
                            return
                        }
                        if (swipeDir <= 0) {
                            // this is a drag or failed swipe. recover immediately
                            mCallback.clearView(mRecyclerView, prevSelected)
                            // full cleanup will happen on onDrawOver
                        } else {
                            // wait until remove animation is complete.
                            mPendingCleanup.add(prevSelected.itemView)
                            mIsPendingCleanup = true
                            if (swipeDir > 0) {
                                // Animation might be ended by other animators during a layout.
                                // We defer callback to avoid editing adapter during a layout.
                                postDispatchSwipe(this, swipeDir)
                            }
                        }
                        // removed from the list after it is drawn for the last time
                        if (mOverdrawChild === prevSelected.itemView) {
                            removeChildDrawingOrderCallbackIfNecessary(prevSelected.itemView)
                        }
                    }
                }
                val duration = mCallback.getAnimationDuration(
                    mRecyclerView!!, animationType,
                    targetTranslateX - currentTranslateX, targetTranslateY - currentTranslateY
                )
                rv.setDuration(duration)
                mRecoverAnimations.add(rv)
                rv.start()
                preventLayout = true
            } else {
                removeChildDrawingOrderCallbackIfNecessary(prevSelected.itemView)
                mCallback.clearView(mRecyclerView, prevSelected)
            }
            mSelected = null
        }
        if (selected != null) {
            mSelectedFlags = mCallback.getAbsoluteMovementFlags(
                mRecyclerView,
                selected
            ) and actionStateMask shr (mActionState
                    * DIRECTION_FLAG_COUNT)
            mSelectedStartX = selected.itemView.left.toFloat()
            mSelectedStartY = selected.itemView.top.toFloat()
            mSelected = selected
            if (actionState == ACTION_STATE_DRAG) {
                mSelected!!.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        val rvParent = mRecyclerView!!.parent
        rvParent?.requestDisallowInterceptTouchEvent(mSelected != null)
        if (!preventLayout) {
            mRecyclerView!!.layoutManager!!.requestSimpleAnimationsInNextLayout()
        }
        mCallback.onSelectedChanged(mSelected, mActionState)
        mRecyclerView!!.invalidate()
    }

    fun postDispatchSwipe(anim: RecoverAnimation, swipeDir: Int) {
        // wait until animations are complete.
        mRecyclerView!!.post(object : Runnable {
            override fun run() {
                if (mRecyclerView != null && mRecyclerView!!.isAttachedToWindow
                    && !anim.mOverridden
                    && anim.mViewHolder.adapterPosition != RecyclerView.NO_POSITION
                ) {
                    val animator = mRecyclerView!!.itemAnimator
                    // if animator is running or we have other active recover animations, we try
                    // not to call onSwiped because DefaultItemAnimator is not good at merging
                    // animations. Instead, we wait and batch.
                    if ((animator == null || !animator.isRunning(null)) && !hasRunningRecoverAnim()) {
                        mCallback.onSwiped(anim.mViewHolder, swipeDir)
                    } else {
                        mRecyclerView!!.post(this)
                    }
                }
            }
        })
    }

    fun hasRunningRecoverAnim(): Boolean {
        val size = mRecoverAnimations.size
        for (i in 0 until size) {
            if (!mRecoverAnimations[i].mEnded) {
                return true
            }
        }
        return false
    }

    /**
     * If user drags the view to the edge, trigger a scroll if necessary.
     */
    fun scrollIfNecessary(): Boolean {
        if (mSelected == null) {
            mDragScrollStartTimeInMs = Long.MIN_VALUE
            return false
        }
        val now = System.currentTimeMillis()
        val scrollDuration =
            if (mDragScrollStartTimeInMs == Long.MIN_VALUE) 0 else now - mDragScrollStartTimeInMs
        val lm = mRecyclerView!!.layoutManager
        if (mTmpRect == null) {
            mTmpRect = Rect()
        }
        var scrollX = 0
        var scrollY = 0
        lm!!.calculateItemDecorationsForChild(mSelected!!.itemView, mTmpRect!!)
        if (lm.canScrollHorizontally()) {
            val curX = (mSelectedStartX + mDx).toInt()
            val leftDiff = curX - mTmpRect!!.left - mRecyclerView!!.paddingLeft
            if (mDx < 0 && leftDiff < 0) {
                scrollX = leftDiff
            } else if (mDx > 0) {
                val rightDiff =
                    curX + mSelected!!.itemView.width + mTmpRect!!.right - (mRecyclerView!!.width
                            - mRecyclerView!!.paddingRight)
                if (rightDiff > 0) {
                    scrollX = rightDiff
                }
            }
        }
        if (lm.canScrollVertically()) {
            val curY = (mSelectedStartY + mDy).toInt()
            val topDiff = curY - mTmpRect!!.top - mRecyclerView!!.paddingTop
            if (mDy < 0 && topDiff < 0) {
                scrollY = topDiff
            } else if (mDy > 0) {
                val bottomDiff =
                    curY + mSelected!!.itemView.height + mTmpRect!!.bottom - (mRecyclerView!!.height
                            - mRecyclerView!!.paddingBottom)
                if (bottomDiff > 0) {
                    scrollY = bottomDiff
                }
            }
        }
        if (scrollX != 0) {
            scrollX = mCallback.interpolateOutOfBoundsScroll(
                mRecyclerView, mSelected!!.itemView.width,
                scrollX, mRecyclerView!!.width, scrollDuration
            )
        }
        if (scrollY != 0) {
            scrollY = mCallback.interpolateOutOfBoundsScroll(
                mRecyclerView, mSelected!!.itemView.height,
                scrollY, mRecyclerView!!.height, scrollDuration
            )
        }
        if (scrollX != 0 || scrollY != 0) {
            if (mDragScrollStartTimeInMs == Long.MIN_VALUE) {
                mDragScrollStartTimeInMs = now
            }
            mRecyclerView!!.scrollBy(scrollX, scrollY)
            return true
        }
        mDragScrollStartTimeInMs = Long.MIN_VALUE
        return false
    }

    private fun findSwapTargets(viewHolder: ViewHolder): List<ViewHolder> {
        if (mSwapTargets == null) {
            mSwapTargets = ArrayList()
            mDistances = ArrayList()
        } else {
            mSwapTargets!!.clear()
            mDistances!!.clear()
        }
        val margin = mCallback.getBoundingBoxMargin()
        val left = Math.round(mSelectedStartX + mDx) - margin
        val top = Math.round(mSelectedStartY + mDy) - margin
        val right = left + viewHolder.itemView.width + 2 * margin
        val bottom = top + viewHolder.itemView.height + 2 * margin
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val lm = mRecyclerView!!.layoutManager
        val childCount = lm!!.childCount
        for (i in 0 until childCount) {
            val other = lm.getChildAt(i)
            if (other === viewHolder.itemView) {
                continue  //myself!
            }
            if (other!!.bottom < top || other.top > bottom || other.right < left || other.left > right) {
                continue
            }
            val otherVh = mRecyclerView!!.getChildViewHolder(other)
            if (mCallback.canDropOver(mRecyclerView, mSelected, otherVh)) {
                // find the index to add
                val dx = Math.abs(centerX - (other.left + other.right) / 2)
                val dy = Math.abs(centerY - (other.top + other.bottom) / 2)
                val dist = dx * dx + dy * dy
                var pos = 0
                val cnt = mSwapTargets!!.size
                for (j in 0 until cnt) {
                    if (dist > mDistances!![j]) {
                        pos++
                    } else {
                        break
                    }
                }
                mSwapTargets!!.add(pos, otherVh)
                mDistances!!.add(pos, dist)
            }
        }
        return mSwapTargets!!
    }

    /**
     * Checks if we should swap w/ another view holder.
     */
    fun moveIfNecessary(viewHolder: ViewHolder) {
        if (mRecyclerView!!.isLayoutRequested) {
            return
        }
        if (mActionState != ACTION_STATE_DRAG) {
            return
        }
        val threshold = mCallback.getMoveThreshold(viewHolder)
        val x = (mSelectedStartX + mDx).toInt()
        val y = (mSelectedStartY + mDy).toInt()
        if (Math.abs(y - viewHolder.itemView.top) < viewHolder.itemView.height * threshold
            && Math.abs(x - viewHolder.itemView.left)
            < viewHolder.itemView.width * threshold
        ) {
            return
        }
        val swapTargets = findSwapTargets(viewHolder)
        if (swapTargets.size == 0) {
            return
        }
        // may swap.
        val target = mCallback.chooseDropTarget(viewHolder, swapTargets, x, y)
        if (target == null) {
            mSwapTargets!!.clear()
            mDistances!!.clear()
            return
        }
        val toPosition = target.adapterPosition
        val fromPosition = viewHolder.adapterPosition
        if (mCallback.onMove(mRecyclerView, viewHolder, target)) {
            // keep target visible
            mCallback.onMoved(mRecyclerView, viewHolder, fromPosition, target, toPosition, x, y)
        }
    }

    override fun onChildViewAttachedToWindow(view: View) {}
    override fun onChildViewDetachedFromWindow(view: View) {
        removeChildDrawingOrderCallbackIfNecessary(view)
        val holder = mRecyclerView!!.getChildViewHolder(view) ?: return
        if (mSelected != null && holder === mSelected) {
            select(null, ACTION_STATE_IDLE)
        } else {
            endRecoverAnimation(holder, false) // this may push it into pending cleanup list.
            if (mPendingCleanup.remove(holder.itemView)) {
                mCallback.clearView(mRecyclerView, holder)
            }
        }
    }

    /**
     * Returns the animation type or 0 if cannot be found.
     */
    fun endRecoverAnimation(viewHolder: ViewHolder?, override: Boolean): Int {
        val recoverAnimSize = mRecoverAnimations.size
        for (i in recoverAnimSize - 1 downTo 0) {
            val anim = mRecoverAnimations[i]
            if (anim.mViewHolder === viewHolder) {
                anim.mOverridden = anim.mOverridden or override
                if (!anim.mEnded) {
                    anim.cancel()
                }
                mRecoverAnimations.removeAt(i)
                return anim.mAnimationType
            }
        }
        return 0
    }

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView,
        state: State
    ) {
        outRect.setEmpty()
    }

    fun obtainVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker!!.recycle()
        }
        mVelocityTracker = VelocityTracker.obtain()
    }

    private fun releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker!!.recycle()
            mVelocityTracker = null
        }
    }

    private fun findSwipedView(motionEvent: MotionEvent): ViewHolder? {
        val lm = mRecyclerView!!.layoutManager
        if (mActivePointerId == ACTIVE_POINTER_ID_NONE) {
            return null
        }
        val pointerIndex = motionEvent.findPointerIndex(mActivePointerId)
        val dx = motionEvent.getX(pointerIndex) - mInitialTouchX
        val dy = motionEvent.getY(pointerIndex) - mInitialTouchY
        val absDx = Math.abs(dx)
        val absDy = Math.abs(dy)
        if (absDx < mSlop && absDy < mSlop) {
            return null
        }
        if (absDx > absDy && lm!!.canScrollHorizontally()) {
            return null
        } else if (absDy > absDx && lm!!.canScrollVertically()) {
            return null
        }
        val child = findChildView(motionEvent) ?: return null
        return mRecyclerView!!.getChildViewHolder(child)
    }

    /**
     * Checks whether we should select a View for swiping.
     */
    fun checkSelectForSwipe(action: Int, motionEvent: MotionEvent, pointerIndex: Int): Boolean {
        if (mSelected != null || action != MotionEvent.ACTION_MOVE || mActionState == ACTION_STATE_DRAG || !mCallback.isItemViewSwipeEnabled()) {
            return false
        }
        if (mRecyclerView!!.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
            return false
        }
        val vh = findSwipedView(motionEvent) ?: return false
        val movementFlags = mCallback.convertToAbsoluteDirection(
            Callback.makeMovementFlags(
                0,
                (mCallback as SwipeableTouchHelperCallback).getAllowedSwipeDirectionsMovementFlags(
                    vh
                )
            ),
            ViewCompat.getLayoutDirection(mRecyclerView!!)
        )
        val swipeFlags =
            movementFlags and ACTION_MODE_SWIPE_MASK shr DIRECTION_FLAG_COUNT * ACTION_STATE_SWIPE
        if (swipeFlags == 0) {
            return false
        }

        // mDx and mDy are only set in allowed directions. We use custom x/y here instead of
        // updateDxDy to avoid swiping if user moves more in the other direction
        val x = motionEvent.getX(pointerIndex)
        val y = motionEvent.getY(pointerIndex)

        // Calculate the distance moved
        val dx = x - mInitialTouchX
        val dy = y - mInitialTouchY
        // swipe target is chose w/o applying flags so it does not really check if swiping in that
        // direction is allowed. This why here, we use mDx mDy to check slope value again.
        val absDx = Math.abs(dx)
        val absDy = Math.abs(dy)
        if (absDx < mSlop && absDy < mSlop) {
            return false
        }
        if (absDx > absDy) {
            if (dx < 0 && swipeFlags and LEFT == 0) {
                return false
            }
            if (dx > 0 && swipeFlags and RIGHT == 0) {
                return false
            }
        } else {
            if (dy < 0 && swipeFlags and UP == 0) {
                return false
            }
            if (dy > 0 && swipeFlags and DOWN == 0) {
                return false
            }
        }
        mDy = 0f
        mDx = mDy
        mActivePointerId = motionEvent.getPointerId(0)
        select(vh, ACTION_STATE_SWIPE)
        return true
    }

    fun findChildView(event: MotionEvent): View? {
        // first check elevated views, if none, then call RV
        val x = event.x
        val y = event.y
        if (mSelected != null) {
            val selectedView = mSelected!!.itemView
            if (hitTest(selectedView, x, y, mSelectedStartX + mDx, mSelectedStartY + mDy)) {
                return selectedView
            }
        }
        for (i in mRecoverAnimations.indices.reversed()) {
            val anim = mRecoverAnimations[i]
            val view = anim.mViewHolder.itemView
            if (hitTest(view, x, y, anim.mX, anim.mY)) {
                return view
            }
        }
        return mRecyclerView!!.findChildViewUnder(x, y)
    }

    /**
     * Starts dragging the provided ViewHolder. By default, ItemTouchHelper starts a drag when a
     * View is long pressed. You can disable that behavior by overriding
     * [Callback.isLongPressDragEnabled].
     *
     *
     * For this method to work:
     *
     *  * The provided ViewHolder must be a child of the RecyclerView to which this
     * ItemTouchHelper
     * is attached.
     *  * [Callback] must have dragging enabled.
     *  * There must be a previous touch event that was reported to the ItemTouchHelper
     * through RecyclerView's ItemTouchListener mechanism. As long as no other ItemTouchListener
     * grabs previous events, this should work as expected.
     *
     *
     * For example, if you would like to let your user to be able to drag an Item by touching one
     * of its descendants, you may implement it as follows:
     * <pre>
     * viewHolder.dragButton.setOnTouchListener(new View.OnTouchListener() {
     * public boolean onTouch(View v, MotionEvent event) {
     * if (MotionEvent.getActionMasked(event) == MotionEvent.ACTION_DOWN) {
     * mItemTouchHelper.startDrag(viewHolder);
     * }
     * return false;
     * }
     * });
    </pre> *
     *
     *
     *
     * @param viewHolder The ViewHolder to start dragging. It must be a direct child of
     * RecyclerView.
     * @see Callback.isItemViewSwipeEnabled
     */
    fun startDrag(viewHolder: ViewHolder) {
        if (!mCallback.hasDragFlag(mRecyclerView, viewHolder)) {
            Log.e(TAG, "Start drag has been called but dragging is not enabled")
            return
        }
        if (viewHolder.itemView.parent !== mRecyclerView) {
            Log.e(
                TAG, "Start drag has been called with a view holder which is not a child of "
                        + "the RecyclerView which is controlled by this ItemTouchHelper."
            )
            return
        }
        obtainVelocityTracker()
        mDy = 0f
        mDx = mDy
        select(viewHolder, ACTION_STATE_DRAG)
    }

    /**
     * Starts swiping the provided ViewHolder. By default, ItemTouchHelper starts swiping a View
     * when user swipes their finger (or mouse pointer) over the View. You can disable this
     * behavior
     * by overriding [Callback]
     *
     *
     * For this method to work:
     *
     *  * The provided ViewHolder must be a child of the RecyclerView to which this
     * ItemTouchHelper is attached.
     *  * [Callback] must have swiping enabled.
     *  * There must be a previous touch event that was reported to the ItemTouchHelper
     * through RecyclerView's ItemTouchListener mechanism. As long as no other ItemTouchListener
     * grabs previous events, this should work as expected.
     *
     *
     * For example, if you would like to let your user to be able to swipe an Item by touching one
     * of its descendants, you may implement it as follows:
     * <pre>
     * viewHolder.dragButton.setOnTouchListener(new View.OnTouchListener() {
     * public boolean onTouch(View v, MotionEvent event) {
     * if (MotionEvent.getActionMasked(event) == MotionEvent.ACTION_DOWN) {
     * mItemTouchHelper.startSwipe(viewHolder);
     * }
     * return false;
     * }
     * });
    </pre> *
     *
     * @param viewHolder The ViewHolder to start swiping. It must be a direct child of
     * RecyclerView.
     */
    fun startSwipe(viewHolder: ViewHolder) {
        if (!mCallback.hasSwipeFlag(mRecyclerView, viewHolder)) {
            Log.e(TAG, "Start swipe has been called but swiping is not enabled")
            return
        }
        if (viewHolder.itemView.parent !== mRecyclerView) {
            Log.e(
                TAG, "Start swipe has been called with a view holder which is not a child of "
                        + "the RecyclerView controlled by this ItemTouchHelper."
            )
            return
        }
        obtainVelocityTracker()
        mDy = 0f
        mDx = mDy
        select(viewHolder, ACTION_STATE_SWIPE)
    }

    fun findAnimation(event: MotionEvent): RecoverAnimation? {
        if (mRecoverAnimations.isEmpty()) {
            return null
        }
        val target = findChildView(event)
        for (i in mRecoverAnimations.indices.reversed()) {
            val anim = mRecoverAnimations[i]
            if (anim.mViewHolder.itemView === target) {
                return anim
            }
        }
        return null
    }

    fun updateDxDy(ev: MotionEvent, directionFlags: Int, pointerIndex: Int) {
        val x = ev.getX(pointerIndex)
        val y = ev.getY(pointerIndex)

        // Calculate the distance moved
        mDx = x - mInitialTouchX
        mDy = y - mInitialTouchY
        if (directionFlags and LEFT == 0) {
            mDx = Math.max(0f, mDx)
        }
        if (directionFlags and RIGHT == 0) {
            mDx = Math.min(0f, mDx)
        }
        if (directionFlags and UP == 0) {
            mDy = Math.max(0f, mDy)
        }
        if (directionFlags and DOWN == 0) {
            mDy = Math.min(0f, mDy)
        }
    }

    private fun swipeIfNecessary(viewHolder: ViewHolder): Int {
        if (mActionState == ACTION_STATE_DRAG) {
            return 0
        }
        val originalMovementFlags = mCallback.getMovementFlags(mRecyclerView, viewHolder)
        val absoluteMovementFlags = mCallback.convertToAbsoluteDirection(
            originalMovementFlags,
            ViewCompat.getLayoutDirection(mRecyclerView!!)
        )
        val flags = absoluteMovementFlags and ACTION_MODE_SWIPE_MASK shr (ACTION_STATE_SWIPE
                * DIRECTION_FLAG_COUNT)
        if (flags == 0) {
            return 0
        }
        val originalFlags = originalMovementFlags and ACTION_MODE_SWIPE_MASK shr (ACTION_STATE_SWIPE
                * DIRECTION_FLAG_COUNT)
        var swipeDir: Int
        if (Math.abs(mDx) > Math.abs(mDy)) {
            if (checkHorizontalSwipe(viewHolder, flags).also { swipeDir = it } > 0) {
                // if swipe dir is not in original flags, it should be the relative direction
                return if (originalFlags and swipeDir == 0) {
                    // convert to relative
                    Callback.convertToRelativeDirection(
                        swipeDir,
                        ViewCompat.getLayoutDirection(mRecyclerView!!)
                    )
                } else swipeDir
            }
            if (checkVerticalSwipe(viewHolder, flags).also { swipeDir = it } > 0) {
                return swipeDir
            }
        } else {
            if (checkVerticalSwipe(viewHolder, flags).also { swipeDir = it } > 0) {
                return swipeDir
            }
            if (checkHorizontalSwipe(viewHolder, flags).also { swipeDir = it } > 0) {
                // if swipe dir is not in original flags, it should be the relative direction
                return if (originalFlags and swipeDir == 0) {
                    // convert to relative
                    Callback.convertToRelativeDirection(
                        swipeDir,
                        ViewCompat.getLayoutDirection(mRecyclerView!!)
                    )
                } else swipeDir
            }
        }
        return 0
    }

    private fun checkHorizontalSwipe(viewHolder: ViewHolder, flags: Int): Int {
        if (flags and (LEFT or RIGHT) != 0) {
            val dirFlag = if (mDx > 0) RIGHT else LEFT
            if (mVelocityTracker != null && mActivePointerId > -1) {
                mVelocityTracker!!.computeCurrentVelocity(
                    PIXELS_PER_SECOND,
                    mCallback.getSwipeVelocityThreshold(mMaxSwipeVelocity)
                )
                val xVelocity = mVelocityTracker!!.getXVelocity(mActivePointerId)
                val yVelocity = mVelocityTracker!!.getYVelocity(mActivePointerId)
                val velDirFlag = if (xVelocity > 0f) RIGHT else LEFT
                val absXVelocity = Math.abs(xVelocity)
                if (velDirFlag and flags != 0 && dirFlag == velDirFlag && absXVelocity >= mCallback.getSwipeEscapeVelocity(
                        mSwipeEscapeVelocity
                    ) && absXVelocity > Math.abs(yVelocity)
                ) {
                    return velDirFlag
                }
            }
            val threshold = mRecyclerView!!.width * mCallback.getSwipeThreshold(viewHolder)
            if (flags and dirFlag != 0 && Math.abs(mDx) > threshold) {
                return dirFlag
            }
        }
        return 0
    }

    private fun checkVerticalSwipe(viewHolder: ViewHolder, flags: Int): Int {
        if (flags and (UP or DOWN) != 0) {
            val dirFlag = if (mDy > 0) DOWN else UP
            if (mVelocityTracker != null && mActivePointerId > -1) {
                mVelocityTracker!!.computeCurrentVelocity(
                    PIXELS_PER_SECOND,
                    mCallback.getSwipeVelocityThreshold(mMaxSwipeVelocity)
                )
                val xVelocity = mVelocityTracker!!.getXVelocity(mActivePointerId)
                val yVelocity = mVelocityTracker!!.getYVelocity(mActivePointerId)
                val velDirFlag = if (yVelocity > 0f) DOWN else UP
                val absYVelocity = Math.abs(yVelocity)
                if (velDirFlag and flags != 0 && velDirFlag == dirFlag && absYVelocity >= mCallback.getSwipeEscapeVelocity(
                        mSwipeEscapeVelocity
                    ) && absYVelocity > Math.abs(xVelocity)
                ) {
                    return velDirFlag
                }
            }
            val threshold = mRecyclerView!!.height * mCallback.getSwipeThreshold(viewHolder)
            if (flags and dirFlag != 0 && Math.abs(mDy) > threshold) {
                return dirFlag
            }
        }
        return 0
    }

    private fun addChildDrawingOrderCallback() {
        if (Build.VERSION.SDK_INT >= 21) {
            return  // we use elevation on Lollipop
        }
        if (mChildDrawingOrderCallback == null) {
            mChildDrawingOrderCallback = ChildDrawingOrderCallback { childCount, i ->
                if (mOverdrawChild == null) {
                    return@ChildDrawingOrderCallback i
                }
                var childPosition = mOverdrawChildPosition
                if (childPosition == -1) {
                    childPosition = mRecyclerView!!.indexOfChild(mOverdrawChild)
                    mOverdrawChildPosition = childPosition
                }
                if (i == childCount - 1) {
                    return@ChildDrawingOrderCallback childPosition
                }
                if (i < childPosition) i else i + 1
            }
        }
        mRecyclerView!!.setChildDrawingOrderCallback(mChildDrawingOrderCallback)
    }

    fun removeChildDrawingOrderCallbackIfNecessary(view: View) {
        if (view === mOverdrawChild) {
            mOverdrawChild = null
            // only remove if we've added
            if (mChildDrawingOrderCallback != null) {
                mRecyclerView!!.setChildDrawingOrderCallback(null)
            }
        }
    }

    /**
     * An interface which can be implemented by LayoutManager for better integration with
     * [ItemTouchHelper].
     */
    interface ViewDropHandler {
        /**
         * Called by the [ItemTouchHelper] after a View is dropped over another View.
         *
         *
         * A LayoutManager should implement this interface to get ready for the upcoming move
         * operation.
         *
         *
         * For example, LinearLayoutManager sets up a "scrollToPositionWithOffset" calls so that
         * the View under drag will be used as an anchor View while calculating the next layout,
         * making layout stay consistent.
         *
         * @param view The View which is being dragged. It is very likely that user is still
         * dragging this View so there might be other
         * [.prepareForDrop] after this one.
         * @param target The target view which is being dropped on.
         * @param x The `left` offset of the View that is being dragged. This value
         * includes the movement caused by the user.
         * @param y The `top` offset of the View that is being dragged. This value
         * includes the movement caused by the user.
         */
        fun prepareForDrop(view: View?, target: View?, x: Int, y: Int)
    }

    /**
     * This class is the contract between ItemTouchHelper and your application. It lets you control
     * which touch behaviors are enabled per each ViewHolder and also receive callbacks when user
     * performs these actions.
     *
     *
     * To control which actions user can take on each view, you should override
     * [.getMovementFlags] and return appropriate set
     * of direction flags. ([.LEFT], [.RIGHT], [.START], [.END],
     * [.UP], [.DOWN]). You can use
     * [.makeMovementFlags] to easily construct it. Alternatively, you can use
     * [SimpleCallback].
     *
     *
     * If user drags an item, ItemTouchHelper will call
     * [ onMove(recyclerView, dragged, target)][Callback.onMove].
     * Upon receiving this callback, you should move the item from the old position
     * (`dragged.getAdapterPosition()`) to new position (`target.getAdapterPosition()`)
     * in your adapter and also call [RecyclerView.Adapter.notifyItemMoved].
     * To control where a View can be dropped, you can override
     * [.canDropOver]. When a
     * dragging View overlaps multiple other views, Callback chooses the closest View with which
     * dragged View might have changed positions. Although this approach works for many use cases,
     * if you have a custom LayoutManager, you can override
     * [.chooseDropTarget] to select a
     * custom drop target.
     *
     *
     * When a View is swiped, ItemTouchHelper animates it until it goes out of bounds, then calls
     * [.onSwiped]. At this point, you should update your
     * adapter (e.g. remove the item) and call related Adapter#notify event.
     */
    abstract class Callback {
        companion object {
            const val DEFAULT_DRAG_ANIMATION_DURATION = 200
            const val DEFAULT_SWIPE_ANIMATION_DURATION = 250
            const val RELATIVE_DIR_FLAGS =
                START or END or (START or END shl DIRECTION_FLAG_COUNT) or (START or END shl (2
                        * DIRECTION_FLAG_COUNT))
            var defaultUIUtil: ItemTouchUIUtil? = null
            private const val ABS_HORIZONTAL_DIR_FLAGS =
                LEFT or RIGHT or (LEFT or RIGHT shl DIRECTION_FLAG_COUNT) or (LEFT or RIGHT shl (2
                        * DIRECTION_FLAG_COUNT))
            private val sDragScrollInterpolator = Interpolator { t: Float -> t * t * t * t * t }
            private val sDragViewScrollCapInterpolator = Interpolator { tt: Float ->
                val t = tt - 1.0f
                t * t * t * t * t + 1.0f
            }

            /**
             * Drag scroll speed keeps accelerating until this many milliseconds before being capped.
             */
            private const val DRAG_SCROLL_ACCELERATION_LIMIT_TIME_MS: Long = 2000
            fun convertToRelativeDirection(flags: Int, layoutDirection: Int): Int {
                var flags = flags
                val masked = flags and ABS_HORIZONTAL_DIR_FLAGS
                if (masked == 0) {
                    return flags // does not have any abs flags, good.
                }
                flags = flags and masked.inv() //remove left / right.
                if (layoutDirection == ViewCompat.LAYOUT_DIRECTION_LTR) {
                    // no change. just OR with 2 bits shifted mask and return
                    flags =
                        flags or (masked shl 2) // START is 2 bits after LEFT, END is 2 bits after RIGHT.
                    return flags
                } else {
                    // add RIGHT flag as START
                    flags = flags or (masked shl 1 and ABS_HORIZONTAL_DIR_FLAGS.inv())
                    // first clean RIGHT bit then add LEFT flag as END
                    flags = flags or (masked shl 1 and ABS_HORIZONTAL_DIR_FLAGS shl 2)
                }
                return flags
            }

            fun makeMovementFlags(dragFlags: Int, swipeFlags: Int): Int {
                return makeFlag(ACTION_STATE_IDLE, swipeFlags or dragFlags) or makeFlag(
                    ACTION_STATE_SWIPE,
                    swipeFlags
                ) or makeFlag(ACTION_STATE_DRAG, dragFlags)
            }

            fun makeFlag(actionState: Int, directions: Int): Int {
                return directions shl actionState * DIRECTION_FLAG_COUNT
            }

            init {
                defaultUIUtil = if (Build.VERSION.SDK_INT >= 21) {
                    ItemTouchUIUtilImpl.Api21Impl()
                } else {
                    BaseImpl()
                }
            }
        }

        private var mCachedMaxScrollSpeed = -1
        abstract fun getMovementFlags(
            recyclerView: RecyclerView?,
            viewHolder: ViewHolder?
        ): Int

        fun convertToAbsoluteDirection(flags: Int, layoutDirection: Int): Int {
            var flags = flags
            val masked = flags and RELATIVE_DIR_FLAGS
            if (masked == 0) {
                return flags // does not have any relative flags, good.
            }
            flags = flags and masked.inv() //remove start / end
            when (layoutDirection) {
                ViewCompat.LAYOUT_DIRECTION_LTR -> {
                    // no change. just OR with 2 bits shifted mask and return
                    flags =
                        flags or (masked shr 2) // START is 2 bits after LEFT, END is 2 bits after RIGHT.
                    return flags
                }
                else -> {
                    // add START flag as RIGHT
                    flags = flags or (masked shr 1 and RELATIVE_DIR_FLAGS.inv())
                    // first clean start bit then add END flag as LEFT
                    flags = flags or (masked shr 1 and RELATIVE_DIR_FLAGS shr 2)
                }
            }
            return flags
        }

        fun getAbsoluteMovementFlags(
            recyclerView: RecyclerView?,
            viewHolder: ViewHolder?
        ): Int {
            val flags = getMovementFlags(recyclerView, viewHolder)
            return convertToAbsoluteDirection(flags, ViewCompat.getLayoutDirection(recyclerView!!))
        }

        fun hasDragFlag(
            recyclerView: RecyclerView?,
            viewHolder: ViewHolder?
        ): Boolean {
            val flags = getAbsoluteMovementFlags(recyclerView, viewHolder)
            return flags and ACTION_MODE_DRAG_MASK != 0
        }

        fun hasSwipeFlag(
            recyclerView: RecyclerView?,
            viewHolder: ViewHolder?
        ): Boolean {
            val flags = getAbsoluteMovementFlags(recyclerView, viewHolder)
            return flags and ACTION_MODE_SWIPE_MASK != 0
        }

        fun canDropOver(
            recyclerView: RecyclerView?,
            current: ViewHolder?,
            target: ViewHolder?
        ): Boolean {
            return true
        }

        /**
         * Called when ItemTouchHelper wants to move the dragged item from its old position to
         * the new position.
         *
         *
         * If this method returns true, ItemTouchHelper assumes `viewHolder` has been moved
         * to the adapter position of `target` ViewHolder
         * ([ ViewHolder#getAdapterPosition()][ViewHolder.getAdapterPosition]).
         *
         *
         * If you don't support drag & drop, this method will never be called.
         *
         * @param recyclerView The RecyclerView to which ItemTouchHelper is attached to.
         * @param viewHolder The ViewHolder which is being dragged by the user.
         * @param target The ViewHolder over which the currently active item is being
         * dragged.
         * @return True if the `viewHolder` has been moved to the adapter position of
         * `target`.
         * @see .onMoved
         */
        abstract fun onMove(
            recyclerView: RecyclerView?, viewHolder: ViewHolder?,
            target: ViewHolder?
        ): Boolean

        /**
         * Returns whether ItemTouchHelper should start a drag and drop operation if an item is
         * long pressed.
         *
         *
         * Default value returns true but you may want to disable this if you want to start
         * dragging on a custom view touch using [.startDrag].
         *
         * @return True if ItemTouchHelper should start dragging an item when it is long pressed,
         * false otherwise. Default value is `true`.
         * @see .startDrag
         */
        fun isLongPressDragEnabled(): Boolean {
            return true
        }

        /**
         * Returns whether ItemTouchHelper should start a swipe operation if a pointer is swiped
         * over the View.
         *
         *
         * Default value returns true but you may want to disable this if you want to start
         * swiping on a custom view touch using [.startSwipe].
         *
         * @return True if ItemTouchHelper should start swiping an item when user swipes a pointer
         * over the View, false otherwise. Default value is `true`.
         * @see .startSwipe
         */
        fun isItemViewSwipeEnabled(): Boolean {
            return true
        }

        /**
         * When finding views under a dragged view, by default, ItemTouchHelper searches for views
         * that overlap with the dragged View. By overriding this method, you can extend or shrink
         * the search box.
         *
         * @return The extra margin to be added to the hit box of the dragged View.
         */
        fun getBoundingBoxMargin(): Int {
            return 0
        }

        /**
         * Returns the fraction that the user should move the View to be considered as swiped.
         * The fraction is calculated with respect to RecyclerView's bounds.
         *
         *
         * Default value is .5f, which means, to swipe a View, user must move the View at least
         * half of RecyclerView's width or height, depending on the swipe direction.
         *
         * @param viewHolder The ViewHolder that is being dragged.
         * @return A float value that denotes the fraction of the View size. Default value
         * is .5f .
         */
        open fun getSwipeThreshold(viewHolder: ViewHolder?): Float {
            return .5f
        }

        /**
         * Returns the fraction that the user should move the View to be considered as it is
         * dragged. After a view is moved this amount, ItemTouchHelper starts checking for Views
         * below it for a possible drop.
         *
         * @param viewHolder The ViewHolder that is being dragged.
         * @return A float value that denotes the fraction of the View size. Default value is
         * .5f .
         */
        fun getMoveThreshold(viewHolder: ViewHolder?): Float {
            return .5f
        }

        /**
         * Defines the minimum velocity which will be considered as a swipe action by the user.
         *
         *
         * You can increase this value to make it harder to swipe or decrease it to make it easier.
         * Keep in mind that ItemTouchHelper also checks the perpendicular velocity and makes sure
         * current direction velocity is larger then the perpendicular one. Otherwise, user's
         * movement is ambiguous. You can change the threshold by overriding
         * [.getSwipeVelocityThreshold].
         *
         *
         * The velocity is calculated in pixels per second.
         *
         *
         * The default framework value is passed as a parameter so that you can modify it with a
         * multiplier.
         *
         * @param defaultValue The default value (in pixels per second) used by the
         * ItemTouchHelper.
         * @return The minimum swipe velocity. The default implementation returns the
         * `defaultValue` parameter.
         * @see .getSwipeVelocityThreshold
         * @see .getSwipeThreshold
         */
        fun getSwipeEscapeVelocity(defaultValue: Float): Float {
            return defaultValue
        }

        /**
         * Defines the maximum velocity ItemTouchHelper will ever calculate for pointer movements.
         *
         *
         * To consider a movement as swipe, ItemTouchHelper requires it to be larger than the
         * perpendicular movement. If both directions reach to the max threshold, none of them will
         * be considered as a swipe because it is usually an indication that user rather tried to
         * scroll then swipe.
         *
         *
         * The velocity is calculated in pixels per second.
         *
         *
         * You can customize this behavior by changing this method. If you increase the value, it
         * will be easier for the user to swipe diagonally and if you decrease the value, user will
         * need to make a rather straight finger movement to trigger a swipe.
         *
         * @param defaultValue The default value(in pixels per second) used by the ItemTouchHelper.
         * @return The velocity cap for pointer movements. The default implementation returns the
         * `defaultValue` parameter.
         * @see .getSwipeEscapeVelocity
         */
        fun getSwipeVelocityThreshold(defaultValue: Float): Float {
            return defaultValue
        }

        /**
         * Called by ItemTouchHelper to select a drop target from the list of ViewHolders that
         * are under the dragged View.
         *
         *
         * Default implementation filters the View with which dragged item have changed position
         * in the drag direction. For instance, if the view is dragged UP, it compares the
         * `view.getTop()` of the two views before and after drag started. If that value
         * is different, the target view passes the filter.
         *
         *
         * Among these Views which pass the test, the one closest to the dragged view is chosen.
         *
         *
         * This method is called on the main thread every time user moves the View. If you want to
         * override it, make sure it does not do any expensive operations.
         *
         * @param selected The ViewHolder being dragged by the user.
         * @param dropTargets The list of ViewHolder that are under the dragged View and
         * candidate as a drop.
         * @param curX The updated left value of the dragged View after drag translations
         * are applied. This value does not include margins added by
         * [RecyclerView.ItemDecoration]s.
         * @param curY The updated top value of the dragged View after drag translations
         * are applied. This value does not include margins added by
         * [RecyclerView.ItemDecoration]s.
         * @return A ViewHolder to whose position the dragged ViewHolder should be
         * moved to.
         */
        fun chooseDropTarget(
            selected: ViewHolder,
            dropTargets: List<ViewHolder>,
            curX: Int,
            curY: Int
        ): ViewHolder? {
            val right = curX + selected.itemView.width
            val bottom = curY + selected.itemView.height
            var winner: ViewHolder? = null
            var winnerScore = -1
            val dx = curX - selected.itemView.left
            val dy = curY - selected.itemView.top
            val targetsSize = dropTargets.size
            for (i in 0 until targetsSize) {
                val target = dropTargets[i]
                if (dx > 0) {
                    val diff = target.itemView.right - right
                    if (diff < 0 && target.itemView.right > selected.itemView.right) {
                        val score = Math.abs(diff)
                        if (score > winnerScore) {
                            winnerScore = score
                            winner = target
                        }
                    }
                }
                if (dx < 0) {
                    val diff = target.itemView.left - curX
                    if (diff > 0 && target.itemView.left < selected.itemView.left) {
                        val score = Math.abs(diff)
                        if (score > winnerScore) {
                            winnerScore = score
                            winner = target
                        }
                    }
                }
                if (dy < 0) {
                    val diff = target.itemView.top - curY
                    if (diff > 0 && target.itemView.top < selected.itemView.top) {
                        val score = Math.abs(diff)
                        if (score > winnerScore) {
                            winnerScore = score
                            winner = target
                        }
                    }
                }
                if (dy > 0) {
                    val diff = target.itemView.bottom - bottom
                    if (diff < 0 && target.itemView.bottom > selected.itemView.bottom) {
                        val score = Math.abs(diff)
                        if (score > winnerScore) {
                            winnerScore = score
                            winner = target
                        }
                    }
                }
            }
            return winner
        }

        /**
         * Called when a ViewHolder is swiped by the user.
         *
         *
         * If you are returning relative directions ([.START] , [.END]) from the
         * [.getMovementFlags] method, this method
         * will also use relative directions. Otherwise, it will use absolute directions.
         *
         *
         * If you don't support swiping, this method will never be called.
         *
         *
         * ItemTouchHelper will keep a reference to the View until it is detached from
         * RecyclerView.
         * As soon as it is detached, ItemTouchHelper will call
         * [.clearView].
         *
         * @param viewHolder The ViewHolder which has been swiped by the user.
         * @param direction The direction to which the ViewHolder is swiped. It is one of
         * [.UP], [.DOWN],
         * [.LEFT] or [.RIGHT]. If your
         * [.getMovementFlags]
         * method
         * returned relative flags instead of [.LEFT] / [.RIGHT];
         * `direction` will be relative as well. ([.START] or [ ][.END]).
         */
        abstract fun onSwiped(viewHolder: ViewHolder?, direction: Int)

        /**
         * Called when the ViewHolder swiped or dragged by the ItemTouchHelper is changed.
         *
         *
         * If you override this method, you should call super.
         *
         * @param viewHolder The new ViewHolder that is being swiped or dragged. Might be null if
         * it is cleared.
         * @param actionState One of [ItemTouchHelper.ACTION_STATE_IDLE],
         * [ItemTouchHelper.ACTION_STATE_SWIPE] or
         * [ItemTouchHelper.ACTION_STATE_DRAG].
         * @see .clearView
         */
        fun onSelectedChanged(viewHolder: ViewHolder?, actionState: Int) {
            if (viewHolder != null) {
                defaultUIUtil!!.onSelected(viewHolder.itemView)
            }
        }

        private fun getMaxDragScroll(recyclerView: RecyclerView?): Int {
            if (mCachedMaxScrollSpeed == -1) {
                mCachedMaxScrollSpeed = recyclerView!!.resources
                    .getDimensionPixelSize(R.dimen.item_touch_helper_max_drag_scroll_per_frame)
            }
            return mCachedMaxScrollSpeed
        }

        fun onMoved(
            recyclerView: RecyclerView?, viewHolder: ViewHolder, fromPos: Int,
            target: ViewHolder, toPos: Int, x: Int, y: Int
        ) {
            val layoutManager = recyclerView!!.layoutManager
            if (layoutManager is ViewDropHandler) {
                (layoutManager as ViewDropHandler).prepareForDrop(
                    viewHolder.itemView, target.itemView, x,
                    y
                )
                return
            }

            // if layout manager cannot handle it, do some guesswork
            if (layoutManager!!.canScrollHorizontally()) {
                val minLeft = layoutManager.getDecoratedLeft(target.itemView)
                if (minLeft <= recyclerView.paddingLeft) {
                    recyclerView.scrollToPosition(toPos)
                }
                val maxRight = layoutManager.getDecoratedRight(target.itemView)
                if (maxRight >= recyclerView.width - recyclerView.paddingRight) {
                    recyclerView.scrollToPosition(toPos)
                }
            }
            if (layoutManager.canScrollVertically()) {
                val minTop = layoutManager.getDecoratedTop(target.itemView)
                if (minTop <= recyclerView.paddingTop) {
                    recyclerView.scrollToPosition(toPos)
                }
                val maxBottom = layoutManager.getDecoratedBottom(target.itemView)
                if (maxBottom >= recyclerView.height - recyclerView.paddingBottom) {
                    recyclerView.scrollToPosition(toPos)
                }
            }
        }

        fun onDraw(
            c: Canvas, parent: RecyclerView?, selected: ViewHolder?,
            recoverAnimationList: List<RecoverAnimation>, actionState: Int, dX: Float,
            dY: Float
        ) {
            val recoverAnimSize = recoverAnimationList.size
            for (i in 0 until recoverAnimSize) {
                val anim = recoverAnimationList[i]
                anim.update()
                val count = c.save()
                onChildDraw(c, parent, anim.mViewHolder, anim.mX, anim.mY, anim.mActionState, false)
                c.restoreToCount(count)
            }
            if (selected != null) {
                val count = c.save()
                onChildDraw(c, parent, selected, dX, dY, actionState, true)
                c.restoreToCount(count)
            }
        }

        fun onDrawOver(
            c: Canvas, parent: RecyclerView, selected: ViewHolder?,
            recoverAnimationList: MutableList<RecoverAnimation>, actionState: Int, dX: Float,
            dY: Float
        ) {
            val recoverAnimSize = recoverAnimationList.size
            for (i in 0 until recoverAnimSize) {
                val anim = recoverAnimationList[i]
                val count = c.save()
                onChildDrawOver(
                    c,
                    parent,
                    anim.mViewHolder,
                    anim.mX,
                    anim.mY,
                    anim.mActionState,
                    false
                )
                c.restoreToCount(count)
            }
            if (selected != null) {
                val count = c.save()
                onChildDrawOver(c, parent, selected, dX, dY, actionState, true)
                c.restoreToCount(count)
            }
            var hasRunningAnimation = false
            for (i in recoverAnimSize - 1 downTo 0) {
                val anim = recoverAnimationList[i]
                if (anim.mEnded && !anim.mIsPendingCleanup) {
                    recoverAnimationList.removeAt(i)
                } else if (!anim.mEnded) {
                    hasRunningAnimation = true
                }
            }
            if (hasRunningAnimation) {
                parent.invalidate()
            }
        }

        open fun clearView(recyclerView: RecyclerView?, viewHolder: ViewHolder) {
            defaultUIUtil!!.clearView(viewHolder.itemView)
        }

        open fun onChildDraw(
            c: Canvas?, recyclerView: RecyclerView?, viewHolder: ViewHolder, dX: Float,
            dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            defaultUIUtil!!.onDraw(
                c, recyclerView, viewHolder.itemView, dX, dY, actionState,
                isCurrentlyActive
            )
        }

        private fun onChildDrawOver(
            c: Canvas?, recyclerView: RecyclerView?, viewHolder: ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            defaultUIUtil!!.onDrawOver(
                c, recyclerView, viewHolder.itemView, dX, dY, actionState,
                isCurrentlyActive
            )
        }

        open fun getAnimationDuration(
            recyclerView: RecyclerView, animationType: Int, animateDx: Float,
            animateDy: Float
        ): Long {
            val itemAnimator = recyclerView.itemAnimator
            return if (itemAnimator == null) {
                if (animationType == ANIMATION_TYPE_DRAG) DEFAULT_DRAG_ANIMATION_DURATION.toLong() else DEFAULT_SWIPE_ANIMATION_DURATION.toLong()
            } else {
                if (animationType == ANIMATION_TYPE_DRAG) itemAnimator.moveDuration else itemAnimator.removeDuration
            }
        }

        fun interpolateOutOfBoundsScroll(
            recyclerView: RecyclerView?, viewSize: Int,
            viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long
        ): Int {
            val maxScroll = getMaxDragScroll(recyclerView)
            val absOutOfBounds = abs(viewSizeOutOfBounds)
            val direction = sign(viewSizeOutOfBounds.toFloat()).toInt()
            // might be negative if other direction
            val outOfBoundsRatio = min(1f, 1f * absOutOfBounds / viewSize)
            val cappedScroll =
                (direction * maxScroll * sDragViewScrollCapInterpolator.getInterpolation(
                    outOfBoundsRatio
                )).toInt()
            val timeRatio: Float = when {
                msSinceStartScroll > DRAG_SCROLL_ACCELERATION_LIMIT_TIME_MS -> {
                    1f
                }
                else -> {
                    msSinceStartScroll.toFloat() / DRAG_SCROLL_ACCELERATION_LIMIT_TIME_MS
                }
            }
            val value = (cappedScroll * sDragScrollInterpolator.getInterpolation(
                timeRatio
            )).toInt()
            return if (value == 0) {
                if (viewSizeOutOfBounds > 0) 1 else -1
            } else value
        }
    }

    abstract class SimpleCallback(
        private var mDefaultDragDirs: Int,
        private var mDefaultSwipeDirs: Int
    ) : Callback() {
        fun setDefaultSwipeDirs(defaultSwipeDirs: Int) {
            mDefaultSwipeDirs = defaultSwipeDirs
        }

        fun setDefaultDragDirs(defaultDragDirs: Int) {
            mDefaultDragDirs = defaultDragDirs
        }

        private fun getSwipeDirs(): Int {
            return mDefaultSwipeDirs
        }

        private fun getDragDirs(): Int {
            return mDefaultDragDirs
        }

        override fun getMovementFlags(
            recyclerView: RecyclerView?,
            viewHolder: ViewHolder?
        ): Int {
            return makeMovementFlags(
                getDragDirs(),
                getSwipeDirs()
            )
        }
    }

    open class RecoverAnimation internal constructor(
        val mViewHolder: ViewHolder,
        val mAnimationType: Int,
        val mActionState: Int,
        private val mStartDx: Float,
        private val mStartDy: Float,
        private val mTargetX: Float,
        private val mTargetY: Float
    ) : Animator.AnimatorListener {
        private val mValueAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)
        var mIsPendingCleanup = false
        var mX = 0f
        var mY = 0f

        // if user starts touching a recovering view, we put it into interaction mode again,
        // instantly.
        var mOverridden = false
        var mEnded = false
        private var mFraction = 0f
        fun setDuration(duration: Long) {
            mValueAnimator.duration = duration
        }

        fun start() {
            mViewHolder.setIsRecyclable(false)
            mValueAnimator.start()
        }

        fun cancel() {
            mValueAnimator.cancel()
        }

        fun setFraction(fraction: Float) {
            mFraction = fraction
        }

        /**
         * We run updates on onDraw method but use the fraction from animator callback.
         * This way, we can sync translate x/y values w/ the animators to avoid one-off frames.
         */
        fun update() {
            mX = when (mStartDx) {
                mTargetX -> {
                    mViewHolder.itemView.translationX
                }
                else -> {
                    mStartDx + mFraction * (mTargetX - mStartDx)
                }
            }
            mY = when (mStartDy) {
                mTargetY -> {
                    mViewHolder.itemView.translationY
                }
                else -> {
                    mStartDy + mFraction * (mTargetY - mStartDy)
                }
            }
        }

        override fun onAnimationStart(animation: Animator) {
            inProgress = true
        }

        override fun onAnimationEnd(animation: Animator) {
            if (!mEnded) {
                mViewHolder.setIsRecyclable(true)
            }
            mEnded = true
            inProgress = false
        }

        override fun onAnimationCancel(animation: Animator) {
            setFraction(1f) //make sure we recover the view's state.
        }

        override fun onAnimationRepeat(animation: Animator) {}

        companion object {
            var inProgress = false
        }

        init {
            mValueAnimator.addUpdateListener { animation: ValueAnimator -> setFraction(animation.animatedFraction) }
            mValueAnimator.setTarget(mViewHolder.itemView)
            mValueAnimator.addListener(this)
            setFraction(0f)
        }
    }

    private inner class ItemTouchHelperGestureListener :
        SimpleOnGestureListener() {
        private var mShouldReactToLongPress = true
        fun doNotReactToLongPress() {
            mShouldReactToLongPress = false
        }

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!mShouldReactToLongPress) {
                return
            }
            val child = findChildView(e)
            if (child != null) {
                val vh = mRecyclerView!!.getChildViewHolder(child)
                if (vh != null) {
                    if (!mCallback.hasDragFlag(mRecyclerView, vh)) {
                        return
                    }
                    val pointerId = e.getPointerId(0)
                    // Long press is deferred.
                    // Check w/ active pointer id to avoid selecting after motion
                    // event is canceled.
                    if (pointerId == mActivePointerId) {
                        val index = e.findPointerIndex(mActivePointerId)
                        val x = e.getX(index)
                        val y = e.getY(index)
                        mInitialTouchX = x
                        mInitialTouchY = y
                        mDy = 0f
                        mDx = mDy
                        if (DEBUG) {
                            Log.d(TAG, "onlong press: x:$mInitialTouchX,y:$mInitialTouchY")
                        }
                        if (mCallback.isLongPressDragEnabled()) {
                            select(vh, ACTION_STATE_DRAG)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Up direction, used for swipe & drag control.
         */
        const val UP = 1

        /**
         * Down direction, used for swipe & drag control.
         */
        const val DOWN = 1 shl 1

        /**
         * Left direction, used for swipe & drag control.
         */
        const val LEFT = 1 shl 2

        /**
         * Right direction, used for swipe & drag control.
         */
        const val RIGHT = 1 shl 3
        // If you change these relative direction values, update Callback#convertToAbsoluteDirection,
        // Callback#convertToRelativeDirection.
        /**
         * Horizontal start direction. Resolved to LEFT or RIGHT depending on RecyclerView's layout
         * direction. Used for swipe & drag control.
         */
        const val START = LEFT shl 2

        /**
         * Horizontal end direction. Resolved to LEFT or RIGHT depending on RecyclerView's layout
         * direction. Used for swipe & drag control.
         */
        const val END = RIGHT shl 2

        /**
         * ItemTouchHelper is in idle state. At this state, either there is no related motion event by
         * the user or latest motion events have not yet triggered a swipe or drag.
         */
        const val ACTION_STATE_IDLE = 0

        /**
         * A View is currently being swiped.
         */
        const val ACTION_STATE_SWIPE = 1

        /**
         * A View is currently being dragged.
         */
        const val ACTION_STATE_DRAG = 2

        /**
         * Animation type for views which are swiped successfully.
         */
        const val ANIMATION_TYPE_SWIPE_SUCCESS = 1 shl 1

        /**
         * Animation type for views which are not completely swiped thus will animate back to their
         * original position.
         */
        const val ANIMATION_TYPE_SWIPE_CANCEL = 1 shl 2

        /**
         * Animation type for views that were dragged and now will animate to their final position.
         */
        const val ANIMATION_TYPE_DRAG = 1 shl 3
        const val TAG = "ItemTouchHelper"
        const val DEBUG = false
        const val ACTIVE_POINTER_ID_NONE = -1
        const val DIRECTION_FLAG_COUNT = 8
        private const val ACTION_MODE_IDLE_MASK = (1 shl DIRECTION_FLAG_COUNT) - 1
        const val ACTION_MODE_SWIPE_MASK = ACTION_MODE_IDLE_MASK shl DIRECTION_FLAG_COUNT
        const val ACTION_MODE_DRAG_MASK = ACTION_MODE_SWIPE_MASK shl DIRECTION_FLAG_COUNT

        /**
         * The unit we are using to track velocity
         */
        private const val PIXELS_PER_SECOND = 1000
        private fun hitTest(child: View, x: Float, y: Float, left: Float, top: Float): Boolean {
            return x >= left && x <= left + child.width && y >= top && y <= top + child.height
        }
    }
}