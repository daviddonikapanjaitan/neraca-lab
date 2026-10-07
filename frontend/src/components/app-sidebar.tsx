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
  ScanSearchIcon,
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
import { INGESTION_SECTIONS } from "@/lib/ingestion-sections"
import { hasPermission, homePath } from "@/lib/permissions"
import type { Permission, User } from "@/lib/types"

interface NavPage {
  title: string
  url: string
}

interface NavItem {
  title: string
  /** the page itself, or the prefix of its sub-pages */
  url: string
  icon: React.ReactNode
  permission: Permission
  /** sub-pages: the entry is a dropdown like the Admin Center */
  pages?: NavPage[]
}

const navMarket: NavItem[] = [
  { title: "Companies", url: "/companies", icon: <Building2Icon />, permission: "COMPANIES" },
  { title: "Screening", url: "/screening", icon: <ScanSearchIcon />, permission: "SCREENING" },
  {
    title: "Ingestion",
    url: "/ingestion",
    icon: <DatabaseZapIcon />,
    permission: "INGESTION",
    pages: INGESTION_SECTIONS.map((s) => ({ title: s.title, url: s.href })),
  },
]

const navAdmin: NavPage[] = [
  { title: "User Management", url: "/admin/users" },
  { title: "Role Management", url: "/admin/roles" },
]

function isActive(pathname: string, url: string) {
  return pathname === url || pathname.startsWith(`${url}/`)
}

/**
 * A sidebar entry with sub-pages (Admin Center, Ingestion): a dropdown, open while one of its pages is shown. In
 * the collapsed sidebar there is no room for the sub-menu: the icon opens the first page.
 */
function NavDropdown({
  title,
  icon,
  active,
  pages,
  pathname,
  iconOnly,
}: {
  title: string
  icon: React.ReactNode
  active: boolean
  pages: NavPage[]
  pathname: string
  iconOnly: boolean
}) {
  if (iconOnly) {
    return (
      <SidebarMenuButton isActive={active} tooltip={title} render={<Link href={pages[0].url} />}>
        {icon}
        <span>{title}</span>
      </SidebarMenuButton>
    )
  }
  return (
    <Collapsible defaultOpen={active} className="group/collapsible">
      <CollapsibleTrigger render={<SidebarMenuButton isActive={active} tooltip={title} />}>
        {icon}
        <span>{title}</span>
        <ChevronRightIcon className="ml-auto transition-transform duration-200 group-data-[open]/collapsible:rotate-90" />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <SidebarMenuSub>
          {pages.map((page) => (
            <SidebarMenuSubItem key={page.url}>
              <SidebarMenuSubButton isActive={isActive(pathname, page.url)} render={<Link href={page.url} />}>
                <span>{page.title}</span>
              </SidebarMenuSubButton>
            </SidebarMenuSubItem>
          ))}
        </SidebarMenuSub>
      </CollapsibleContent>
    </Collapsible>
  )
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
                  {item.pages ? (
                    <NavDropdown
                      title={item.title}
                      icon={item.icon}
                      active={isActive(pathname, item.url)}
                      pages={item.pages}
                      pathname={pathname}
                      iconOnly={iconOnly}
                    />
                  ) : (
                    <SidebarMenuButton
                      isActive={isActive(pathname, item.url)}
                      tooltip={item.title}
                      render={<Link href={item.url} />}
                    >
                      {item.icon}
                      <span>{item.title}</span>
                    </SidebarMenuButton>
                  )}
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
                <NavDropdown
                  title="Admin Center"
                  icon={<ShieldCheckIcon />}
                  active={adminActive}
                  pages={navAdmin}
                  pathname={pathname}
                  iconOnly={iconOnly}
                />
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
