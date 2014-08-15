package com.lookout.jruby

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.War

import org.gradle.api.file.FileTree


class JRubyPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.apply plugin: 'java'
        project.apply plugin: 'war'
        project.extensions.create('jruby', JRubyPluginExtension)

        project.repositories {
            maven {
                // The url is in a closure to ensure that we can overwrite this
                // at runtime and have the right value come through.
                url { project.jruby.gemrepo_url }
            }

            // Required to pull in our warbler-bootstrap dependency
            maven { url 'http://dl.bintray.com/rtyler/jruby' }

            // We'll need jcenter to resolve the jruby .jar deps
            jcenter()
        }

        // Set up a special configuration group for our embedding jars
        project.configurations {
            jrubyEmbeds
            jrubyWar
            gems
        }

        // In order for jrubyWar to work we'll need to pull in the warbler
        // bootstrap code from this artifact
        project.dependencies {
            jrubyEmbeds group: 'com.lookout', name: 'warbler-bootstrap', version: '1.+'
            jrubyWar group: 'org.jruby.rack', name: 'jruby-rack', version: '1.1.+'
            jrubyWar group: 'org.jruby', name: 'jruby-complete', version: '1.7.+'
        }

        project.task('jrubyClean', type: Delete) {
            group 'JRuby'
            description 'Clean up the temporary dirs used by the JRuby plugin'
            mustRunAfter 'clean'
            delete '.gemcache/', '.jarcache/'
        }

        project.task('jrubyCacheGems', type: Copy) {
            group 'JRuby'
            description 'Copy gems from the `gem` dependencies into .gemcache/'
            from project.configurations.gems
            into '.gemcache'
            include '**/*.gem'
        }

        project.task('jrubyPrepareGems') {
            group 'JRuby'
            description 'Prepare the gems from the `gem` dependencies, extracts into vendor/'
            dependsOn project.tasks.jrubyCacheGems

            doLast {
                project.fileTree(dir: '.gemcache/',
                                 include: '*.gem').each { File f ->
                    extractGem(project, f)
                }
            }
        }

        project.task('jrubyCacheJars', type: Copy) {
            group 'JRuby'
            description 'Cache .jar-based dependencies into .jarcache/'
            from project.configurations.jrubyWar
            into ".jarcache"
            include '**/*.jar'
        }

        project.task('jrubyPrepare') {
            group 'JRuby'
            description 'Pre-cache and prepare all dependencies (jars and gems)'
            dependsOn project.tasks.jrubyCacheJars, project.tasks.jrubyPrepareGems
        }

        project.task('jrubyWar', type: War) {
            group 'JRuby'
            description 'Create a executable JRuby-based web archive'
            dependsOn project.tasks.jrubyPrepare

            from "$project.buildDir/classes/main"
            // Bring our vendored gems into the created war file
            webInf {
                from 'vendor'
                into 'gems'
            }

            // Bring the jrubyWar configuration's dependencies into
            // WEB-INF/libs
            classpath project.configurations.jrubyWar

            // note that zipTree call is wrapped in closure so that configuration
            // is only resolved at execution time. This will take the embeds
            // from within the `jrubyEmbeds` configuration and dump them into the war
            from {
                project.configurations.jrubyEmbeds.collect { project.zipTree(it) }
            }

            // By adding the WarMain class as the main-class we can have a
            // runnable war
            manifest { attributes 'Main-Class' : 'com.lookout.jruby.WarMain' }
        }
    }

    /*
     * Take the given .gem filename (e.g. rake-10.3.2.gem) and just return the
     * gem "full name" (e.g. rake-10.3.2)
     */
    static String gemFullNameFromFile(String filename) {
        return filename.replaceAll(~".gem", "")
    }

    Boolean extractGem(Project p, File gem) {
        def gemname = gemFullNameFromFile(gem.getName())
        File extract_dir = new File("./vendor/gems/$gemname")

        if (extract_dir.exists()) {
            return
        }

        p.exec {
            executable "gem"
            args 'install', gem, '--install-dir=./vendor', '--no-ri', '--no-rdoc'
        }
    }
}