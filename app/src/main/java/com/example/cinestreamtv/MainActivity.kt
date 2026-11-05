package com.example.cinestreamtv
# ==============================================================================
# FlixGlass TV - Android TV Debrid Player (Kivy + TorBox)
# File: main.py
# Description: Netflix Liquid Glass UI tailored for Android TV D-Pad Remote Navigation.
# ==============================================================================

import os
import requests
from kivy.app import App
from kivy.lang import Builder
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.image import AsyncImage
from kivy.properties import StringProperty, ListProperty, ObjectProperty
from kivy.core.window import Window

# Set TV 1080p Standard Orientation
Window.size = (1920, 1080)

KV_MARKUP = '''
#:import SmoothTransition kivy.uix.screenmanager.SmoothTransition

:
    background_normal: ''
    background_color: (0.1, 0.1, 0.15, 0.7)
    color: (1, 1, 1, 1)
    font_name: 'Roboto'
    bold: True
    canvas.before:
        Color:
            rgba: (1, 1, 1, 0.2) if self.focus else (0.2, 0.2, 0.25, 0.4)
        Line:
            rounded_rectangle: (self.x, self.y, self.width, self.height, 12)
            width: 2 if self.focus else 1

ScreenManager:
    transition: SmoothTransition()
    HomeScreen:
    SettingsScreen:
    PlayerScreen:

:
    name: 'home'
    BoxLayout:
        orientation: 'vertical'
        canvas.before:
            Color:
                rgba: (0.04, 0.04, 0.06, 1)
            Rectangle:
                pos: self.pos
                size: self.size

        # --- LIQUID GLASS TOP NAVBAR ---
        BoxLayout:
            size_hint_y: 0.1
            padding: [30, 10]
            spacing: 20
            canvas.before:
                Color:
                    rgba: (0.1, 0.1, 0.15, 0.6)
                Rectangle:
                    pos: self.pos
                    size: self.size

            Label:
                text: '[b]FLIX[/b][color=e50914]GLASS[/color]'
                markup: True
                font_size: '24sp'
                size_hint_x: 0.2
                halign: 'left'

            GlassButton:
                text: 'Home'
                size_hint_x: 0.15
            GlassButton:
                text: 'Movies'
                size_hint_x: 0.15
            GlassButton:
                text: 'TV Shows'
                size_hint_x: 0.15
            GlassButton:
                text: 'Settings'
                size_hint_x: 0.15
                on_release: root.manager.current = 'settings'

        # --- HERO BANNER & MEDIA CAROUSEL ---
        BoxLayout:
            orientation: 'vertical'
            padding: 30
            spacing: 20

            # Hero Card
            BoxLayout:
                size_hint_y: 0.4
                padding: 20
                spacing: 20
                canvas.before:
                    Color:
                        rgba: (0.15, 0.15, 0.2, 0.5)
                    RoundedRectangle:
                        pos: self.pos
                        size: self.size
                        radius: [20,]

                AsyncImage:
                    source: 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=600'
                    size_hint_x: 0.3

                BoxLayout:
                    orientation: 'vertical'
                    spacing: 10
                    Label:
                        text: 'CYBERPUNK: NEXUS (2024)'
                        font_size: '28sp'
                        bold: True
                        halign: 'left'
                        text_size: self.size
                    Label:
                        text: 'Instant 4K TorBox Debrid Stream Available'
                        font_size: '14sp'
                        color: (0.1, 0.9, 0.5, 1)
                        halign: 'left'
                        text_size: self.size
                    GlassButton:
                        text: 'Play Debrid Stream'
                        size_hint: (0.4, 0.3)
                        on_release: root.manager.current = 'player'

            # Carousels Grid
            Label:
                text: 'Trending Debrid Downloads'
                font_size: '18sp'
                bold: True
                size_hint_y: 0.08
                halign: 'left'
                text_size: self.size

            ScrollView:
                size_hint_y: 0.5
                GridLayout:
                    id: poster_grid
                    rows: 1
                    spacing: 15
                    size_hint_x: None
                    width: self.minimum_width

:
    name: 'settings'
    BoxLayout:
        orientation: 'vertical'
        padding: 50
        spacing: 20
        Label:
            text: 'TorBox Debrid Configuration'
            font_size: '24sp'
            bold: True
            size_hint_y: 0.1
        
        GlassButton:
            text: 'Save API Key & Return'
            size_hint: (0.5, 0.15)
            pos_hint: {'center_x': 0.5}
            on_release: root.manager.current = 'home'

:
    name: 'player'
    BoxLayout:
        orientation: 'vertical'
        Label:
            text: '[ Video Player Overlay Placeholder ]'
            font_size: '20sp'
        GlassButton:
            text: 'Back to Home'
            size_hint: (0.3, 0.1)
            pos_hint: {'center_x': 0.5}
            on_release: root.manager.current = 'home'
'''

class HomeScreen(Screen):
    def on_enter(self):
        # Dynamically add poster items
        grid = self.ids.poster_grid
        grid.clear_widgets()
        posters = [
            'https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=300',
            'https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=300',
            'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=300',
            'https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?q=80&w=300'
        ]
        for url in posters:
            img = AsyncImage(source=url, size_hint_x=None, width=180)
            grid.add_widget(img)

class SettingsScreen(Screen):
    pass

class PlayerScreen(Screen):
    pass

class FlixGlassApp(App):
    def build(self):
        self.title = "FlixGlass Debrid TV Player"
        return Builder.load_string(KV_MARKUP)

if __name__ == '__main__':
    FlixGlassApp().run()
