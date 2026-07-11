tailwind.config = {
  theme: {
    extend: {
      colors: {
        bg:     'var(--bg)',     bg2:     'var(--bg2)',     bg3:     'var(--bg3)',
        border: 'var(--border)', border2: 'var(--border2)',
        ink:    'var(--text)',   ink2:    'var(--text2)',   ink3:    'var(--text3)',
        green:  'var(--green)',  'green-bg': 'var(--green-bg)',
        blue:   'var(--blue)',   'blue-bg':  'var(--blue-bg)',
        amber:  'var(--amber)',  'amber-bg': 'var(--amber-bg)',
        red:    'var(--red)',    'red-bg':   'var(--red-bg)'
      },
      borderRadius: { card: 'var(--radius)' },
      animation: {
        'pulse-badge': 'pulse-badge 1s ease-in-out infinite',
        'pulse-node':  'pulse 1.2s ease-in-out infinite'
      }
    }
  }
};
