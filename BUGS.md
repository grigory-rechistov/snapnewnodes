Known bugs and limitations
==========================

* Sometimes the plugin chooses a wrong route on a destination way, resulting in a
  completely broken result. Always control visually that the result meets your
  expectations, and use Undo if it does not.

* It is expected that every target segment to snap to will consist of continuous
  sections of the destination way. If the destination way has loops tightly placed
  to themselves resulting in more than one segment being close to a source way
  nodes, the snapping algorithm may choose to jump between them, producing 
  a broken result. Typically, if it is not trivial for a human to figure out
  what an end result should look like because of the target way being
  too tightly spaced to itself, the plugin would not be able to figure it out
  either. Lowering the threshold may help if there is a clearly measurable maximum
  gap between loops. If the target way self-intersects, fix it first. For complex
  cases, do it manually instead.

* Leftover duplicate nodes and unconnected nodes without tags may be present.
  They are detected by the validator and can be automatically fixed by it.

* AngleTreshold is not configurable through the Preferences dialog.

* Deletion of zero angles and duplicate nodes is performed, but sometimes
  fails to do so, or leaves dangling isolated nodes. These issues are detected
  by the validator. Orphan nodes can be automatically deleted. Zero-angled
  segments are reported as self-intersections and should be fixed manually by
  deleting, merging of moving nodes.

* Deletion of zero angles at the first/last node position is not done.



