/* Synthetic GTK3 URI provider. Loaded only by the isolated fixture's GTK process.
 * No RDP protocol emulation: GNOME performs the real URI/CLIPRDR conversion. */
#include <gtk/gtk.h>

static void provide(GtkClipboard *clipboard, GtkSelectionData *selection,
                    guint info, gpointer data)
{
    (void)clipboard;
    (void)info;
    gtk_selection_data_set_uris(selection, data);
}

static void cleared(GtkClipboard *clipboard, gpointer data)
{
    (void)clipboard;
    g_strfreev(data);
}

/* Call only on the existing GTK main thread, with three generated fixture URIs. */
int rdpsync_fixture_offer_uris(const char *const *uris)
{
    GtkTargetEntry target = { "text/uri-list", 0, 0 };
    if (!uris || g_strv_length((gchar **)uris) != 3)
        return 0;
    gchar **owned = g_strdupv((gchar **)uris);
    GtkClipboard *clipboard = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
    if (!gtk_clipboard_set_with_data(clipboard, &target, 1, provide, cleared, owned))
    {
        g_strfreev(owned);
        return 0;
    }
    return 1;
}

/* Metadata readiness only: no URI payload or file-content read. */
int rdpsync_fixture_uris_available(void)
{
    return gtk_clipboard_wait_is_uris_available(gtk_clipboard_get(GDK_SELECTION_CLIPBOARD));
}
