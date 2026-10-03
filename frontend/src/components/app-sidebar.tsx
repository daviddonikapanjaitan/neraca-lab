"use client"

import * as React from "react"
import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import {
  Building2Icon,
  ChevronRightIcon,
  DatabaseZapIcon,
  LandmarkIcon,
  LoaderIcon,
  LogOutIcon,
  ShieldCheckIcon,
} from "lucide-react"

import { UserAvatar } from "@/components/user-avatar"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from "@/components/ui/sidebar"
import { hasPermission, homePath } from "@/lib/permissions"
import type { Permission, User } from "@/lib/types"

const navMarket: { title: string; url: string; icon: React.ReactNode; permission: Permission }[] = [
  { title: "Companies", url: "/companies", icon: <Building2Icon />, permission: "COMPANIES" },
  { title: "Ingestion", url: "/ingestion", icon: <DatabaseZapIcon />, permission: "INGESTION" },
]

const navAdmin = [
  { title: "User Management", url: "/admin/users" },
  { title: "Role Management", url: "/admin/roles" },
]

function isActive(pathname: string, url: string) {
  return pathname === url || pathname.startsWith(`${url}/`)
}

/** Navigation filtered by the user's permissions, the Admin Center group and the user (profile, log out). */
export function AppSidebar({ user, ...props }: React.ComponentProps<typeof Sidebar> & { user: User }) {
  const pathname = usePathname()
  const router = useRouter()
  const { state, isMobile } = useSidebar()
  const [loggingOut, setLoggingOut] = React.useState(false)
  const market = navMarket.filter((item) => hasPermission(user, item.permission))
  const admin = hasPermission(user, "ADMIN")
  const adminActive = pathname.startsWith("/admin")
  const iconOnly = state === "collapsed" && !isMobile

  async function logout() {
    setLoggingOut(true)
    try {
      await fetch("/api/auth/logout", { method: "POST", cache: "no-store" })
    } finally {
      router.replace("/login")
      router.refresh()
    }
  }

  return (
    <Sidebar variant="inset" {...props}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" render={<Link href={homePath(user)} />}>
              <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                <LandmarkIcon className="size-4" />
              </div>
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-semibold">Neraca Lab</span>
                <span className="truncate text-xs text-muted-foreground">
                  Fundamental Analysis
                </span>
              </div>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {market.length > 0 && (
          <SidebarGroup>
            <SidebarGroupLabel>Market</SidebarGroupLabel>
            <SidebarMenu>
              {market.map((item) => (
                <SidebarMenuItem key={item.title}>
                  <SidebarMenuButton
                    isActive={isActive(pathname, item.url)}
                    tooltip={item.title}
                    render={<Link href={item.url} />}
                  >
                    {item.icon}
                    <span>{item.title}</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroup>
        )}
        {admin && (
          <SidebarGroup>
            <SidebarGroupLabel>Administration</SidebarGroupLabel>
            <SidebarMenu>
              <SidebarMenuItem>
                {iconOnly ? (
                  // collapsed sidebar: no room for the sub-menu, the icon opens the first page
                  <SidebarMenuButton isActive={adminActive} tooltip="Admin Center" render={<Link href="/admin/users" />}>
                    <ShieldCheckIcon />
                    <span>Admin Center</span>
                  </SidebarMenuButton>
                ) : (
                  <Collapsible defaultOpen={adminActive} className="group/collapsible">
                    <CollapsibleTrigger
                      render={<SidebarMenuButton isActive={adminActive} tooltip="Admin Center" />}
                    >
                      <ShieldCheckIcon />
                      <span>Admin Center</span>
                      <ChevronRightIcon className="ml-auto transition-transform duration-200 group-data-[open]/collapsible:rotate-90" />
                    </CollapsibleTrigger>
                    <CollapsibleContent>
                      <SidebarMenuSub>
                        {navAdmin.map((item) => (
                          <SidebarMenuSubItem key={item.url}>
                            <SidebarMenuSubButton isActive={isActive(pathname, item.url)} render={<Link href={item.url} />}>
                              <span>{item.title}</span>
                            </SidebarMenuSubButton>
                          </SidebarMenuSubItem>
                        ))}
                      </SidebarMenuSub>
                    </CollapsibleContent>
                  </Collapsible>
                )}
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroup>
        )}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              size="lg"
              isActive={pathname === "/profile"}
              tooltip="Profile"
              render={<Link href="/profile" />}
              className="pr-9"
            >
              <UserAvatar user={user} source="self" className="size-8" />
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-medium">{user.fullName ?? user.username}</span>
                <span className="truncate text-xs text-muted-foreground">{user.email}</span>
              </div>
            </SidebarMenuButton>
            <SidebarMenuAction
              onClick={logout}
              disabled={loggingOut}
              aria-label="Log out"
              title="Log out"
              className="top-1/2! -translate-y-1/2"
            >
              {loggingOut ? <LoaderIcon className="animate-spin" /> : <LogOutIcon />}
            </SidebarMenuAction>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  )
}
