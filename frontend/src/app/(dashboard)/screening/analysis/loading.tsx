import { Skeleton } from "@/components/ui/skeleton"

export default function Loading() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <Skeleton className="h-14 w-full rounded-xl" />
      <Skeleton className="h-[360px] rounded-xl" />
      <Skeleton className="h-[320px] rounded-xl" />
    </div>
  )
}
