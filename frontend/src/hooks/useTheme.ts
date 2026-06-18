import {useEffect, useState} from 'react';
import {applyTheme, getInitialTheme, saveTheme, type Theme} from '../utils/theme';

export function useTheme() {
    const [theme, setTheme] = useState<Theme>(getInitialTheme);

    useEffect(() => {
        applyTheme(theme);
        saveTheme(theme);
    }, [theme]);

    const toggleTheme = () => {
        setTheme((prev) => (prev === 'light' ? 'dark' : 'light'));
    };

    return {theme, toggleTheme};
}
