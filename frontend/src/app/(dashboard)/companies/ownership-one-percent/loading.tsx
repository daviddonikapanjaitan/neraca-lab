import { Skeleton } from "@/components/ui/skeleton"

export default function Loading() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <Skeleton className="h-9 w-full rounded-lg" />
      <Skeleton className="h-8 w-2/3 rounded-lg" />
      <Skeleton className="h-[480px] rounded-xl" />
    </div>
  )
}
