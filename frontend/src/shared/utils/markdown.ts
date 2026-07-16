function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function normalizeHeading(value: string) {
  return value.trim().replace(/^\*\*(.+)\*\*$/, '$1')
}

function formatInline(value: string) {
  let html = escapeHtml(value)

  html = html.replace(/\[([^\]]+)]\((https?:\/\/[^)\s]+)\)/g, (_match, label, url) => {
    return `<a href="${url}" target="_blank" rel="noreferrer">${label}</a>`
  })

  html = html.replace(/(^|[\s(])((?:https?:\/\/)[^\s<)]+)/g, (_match, prefix, url) => {
    return `${prefix}<a href="${url}" target="_blank" rel="noreferrer">${url}</a>`
  })

  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  return html
}

export function renderMarkdown(markdown: string) {
  const lines = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  const html: string[] = []
  let paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []

  const flushParagraph = () => {
    if (!paragraph.length) return
    html.push(`<p>${formatInline(paragraph.join(' '))}</p>`)
    paragraph = []
  }

  const flushList = () => {
    if (!listType || !listItems.length) return
    html.push(`<${listType}>${listItems.map(item => `<li>${formatInline(item)}</li>`).join('')}</${listType}>`)
    listType = null
    listItems = []
  }

  const pushListItem = (type: 'ul' | 'ol', item: string) => {
    flushParagraph()
    if (listType && listType !== type) flushList()
    listType = type
    listItems.push(item)
  }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      flushParagraph()
      flushList()
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      flushParagraph()
      flushList()
      const level = heading[1].length >= 4 ? 4 : 3
      html.push(`<h${level}>${formatInline(normalizeHeading(heading[2]))}</h${level}>`)
      continue
    }

    const unordered = line.match(/^[-*·]\s+(.+)$/)
    if (unordered) {
      pushListItem('ul', unordered[1])
      continue
    }

    const ordered = line.match(/^\d+[.)]\s+(.+)$/)
    if (ordered) {
      pushListItem('ol', ordered[1])
      continue
    }

    flushList()
    paragraph.push(line)
  }

  flushParagraph()
  flushList()
  return html.join('')
}
