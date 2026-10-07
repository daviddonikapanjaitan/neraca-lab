import { Skeleton } from "@/components/ui/skeleton"

/** Every Ingestion page: heading, the page's card, the job summary tiles and the jobs table. */
export default function Loading() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <Skeleton className="h-14 w-full rounded-xl" />
      <Skeleton className="h-[380px] w-full max-w-3xl rounded-xl" />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-[72px] rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-[320px] rounded-xl" />
    </div>
  )
}
