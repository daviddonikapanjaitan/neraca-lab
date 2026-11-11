"use client"

import { useEffect, useRef, useState } from "react"

import { cn } from "@/lib/utils"

/** Pointer travel (px) after which a press is a drag, not a click. */
const DRAG_THRESHOLD = 5

/** A press on these starts no drag: they keep their own click (links, buttons, inputs). */
const INTERACTIVE = "a, button, input, select, textarea, label, [role=button], [data-no-drag]"

/**
 * A wide table that scrolls sideways three ways, inside itself only (the page never moves):
 * <ul>
 *   <li>a scrollbar above the table, kept in step with the one below it;</li>
 *   <li>pressing the left mouse button on the table and dragging slides it (not on a link or button, which keep their
 *       click; a drag never ends in a click);</li>
 *   <li>the usual wheel, trackpad, touch and keyboard scrolling.</li>
 * </ul>
 */
export function DragScroll({ children, className }: { children: React.ReactNode; className?: string }) {
  const top = useRef<HTMLDivElement>(null)
  const body = useRef<HTMLDivElement>(null)
  const [contentWidth, setContentWidth] = useState(0)
  const [overflows, setOverflows] = useState(false)
  const [dragging, setDragging] = useState(false)
  const drag = useRef<{ x: number; left: number; moved: boolean; id: number } | null>(null)

  // the top bar is as wide as the table, and only shown when the table is wider than its box
  useEffect(() => {
    const el = body.current
    if (!el) return
    const measure = () => {
      setContentWidth(el.scrollWidth)
      setOverflows(el.scrollWidth > el.clientWidth + 1)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    if (el.firstElementChild) observer.observe(el.firstElementChild)
    return () => observer.disconnect()
  }, [])

  // the two scrollbars move together (setting an equal position fires no event, so this never loops)
  function onTopScroll() {
    if (top.current && body.current && Math.abs(body.current.scrollLeft - top.current.scrollLeft) >= 1) {
      body.current.scrollLeft = top.current.scrollLeft
    }
  }

  function onBodyScroll() {
    if (top.current && body.current && Math.abs(top.current.scrollLeft - body.current.scrollLeft) >= 1) {
      top.current.scrollLeft = body.current.scrollLeft
    }
  }

  function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    // the left mouse button only: touch and pens scroll natively
    if (e.pointerType !== "mouse" || e.button !== 0 || !overflows) return
    if ((e.target as HTMLElement).closest(INTERACTIVE)) return
    drag.current = { x: e.clientX, left: e.currentTarget.scrollLeft, moved: false, id: e.pointerId }
  }

  function onPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    const dx = e.clientX - d.x
    if (!d.moved) {
      if (Math.abs(dx) < DRAG_THRESHOLD) return
      d.moved = true
      setDragging(true)
      e.currentTarget.setPointerCapture(e.pointerId)
      // no text gets selected while sliding
      window.getSelection()?.removeAllRanges()
    }
    e.currentTarget.scrollLeft = d.left - dx
    e.preventDefault()
  }

  function endDrag(e: React.PointerEvent<HTMLDivElement>) {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    drag.current = null
    if (d.moved) {
      // the click the browser sends after the drag is swallowed (once)
      const swallow = (ev: MouseEvent) => {
        ev.stopPropagation()
        ev.preventDefault()
      }
      window.addEventListener("click", swallow, { capture: true, once: true })
      setTimeout(() => window.removeEventListener("click", swallow, { capture: true }), 0)
    }
    setDragging(false)
  }

  return (
    <div className={cn("min-w-0", className)}>
      {/* the scrollbar above the table */}
      <div
        ref={top}
        onScroll={onTopScroll}
        aria-hidden
        className={cn("overflow-x-auto overflow-y-hidden border-b", !overflows && "hidden")}
      >
        <div style={{ width: contentWidth, height: 1 }} />
      </div>
      <div
        ref={body}
        onScroll={onBodyScroll}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        className={cn(
          // relative: the absolutely placed screen-reader labels stay inside the scroll box, not past the page
          "relative overflow-x-auto",
          overflows && (dragging ? "cursor-grabbing select-none" : "cursor-grab")
        )}
      >
        {children}
      </div>
    </div>
  )
}
