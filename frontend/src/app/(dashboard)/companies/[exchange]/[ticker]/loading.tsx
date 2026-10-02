import { Skeleton } from "@/components/ui/skeleton"

export default function Loading() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <Skeleton className="h-[120px] w-full rounded-xl" />
      <Skeleton className="h-8 w-full max-w-3xl rounded-lg" />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 8 }, (_, i) => (
          <Skeleton key={i} className="h-[76px] rounded-xl" />
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-12">
        <Skeleton className="h-[360px] rounded-xl lg:col-span-8" />
        <Skeleton className="h-[360px] rounded-xl lg:col-span-4" />
      </div>
    </div>
  )
}
